"""The parity gate executes Java, propagates failures, and remains enabled in quick mode."""
from contextlib import ExitStack, redirect_stdout
import io
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from eval import ci_gate


class ConstraintParityGateTest(unittest.TestCase):
    def test_runs_offline_java_task_and_propagates_failure(self):
        with patch.dict('os.environ', {'FORMACRAFT_GRADLE': 'local-gradle'}), \
                patch.object(ci_gate.subprocess, 'run') as run:
            run.return_value.returncode = 7
            self.assertEqual(ci_gate.run_constraint_parity(), 7)
            self.assertEqual(run.call_args.args[0],
                             ['local-gradle', 'constraintParityTest', '--offline', '--console=plain'])
            self.assertEqual(run.call_args.kwargs['cwd'], str(ci_gate._BACKEND_ROOT.parent))

    def test_missing_runtime_and_timeout_cannot_be_reported_as_success(self):
        for error in [FileNotFoundError('gradle unavailable'), subprocess.TimeoutExpired('gradle', 600)]:
            with self.subTest(error=type(error)), patch.object(ci_gate.subprocess, 'run', side_effect=error):
                self.assertEqual(ci_gate.run_constraint_parity(), 2)

    def test_failure_is_fail_fast_between_assembly_and_shape_checks_even_in_quick_mode(self):
        events = []
        steps = ['eval.golden_eval.run_scenarios', 'eval.diversity_eval.run_diversity_scenarios',
                 'eval.ci_gate.run_diversity_negative', 'eval.ci_gate.run_assembly_repair_negative',
                 'eval.ci_gate.run_constraint_parity', 'eval.ci_gate.run_shape_fixtures']
        for failure in (0, 1):
            with self.subTest(failure=failure), ExitStack() as stack:
                events.clear()
                stack.enter_context(redirect_stdout(io.StringIO()))
                for name in steps:
                    short = name.rsplit('.', 1)[1]
                    def call(*args, _short=short, **kwargs):
                        events.append(_short)
                        return failure if _short == 'run_constraint_parity' else 0
                    stack.enter_context(patch(name, side_effect=call))
                unit = stack.enter_context(patch.object(ci_gate, '_run_unittest_modules'))
                self.assertEqual(ci_gate.run_ci_gate(quick=True), failure)
                unit.assert_not_called()
                expected = [step.rsplit('.', 1)[1] for step in steps]
                self.assertEqual(events, expected if failure == 0 else expected[:-1])

    def test_success_requires_nonempty_passing_java_reports(self):
        with TemporaryDirectory() as directory, patch.object(ci_gate.subprocess, 'run') as run, \
                redirect_stdout(io.StringIO()):
            run.return_value.returncode = 0
            repo = Path(directory)
            reports = repo / 'build/test-results/constraintParityTest'
            reports.mkdir(parents=True)
            with patch.object(ci_gate, '_BACKEND_ROOT', repo / 'python_backend'):
                self.assertEqual(ci_gate.run_constraint_parity(), 2)
                for name in ('ConstraintParityTest', 'MutationRegionPolicyTest'):
                    path = reports / f'TEST-com.formacraft.server.build.{name}.xml'
                    path.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"/>', encoding='utf-8')
                self.assertEqual(ci_gate.run_constraint_parity(), 0)
                path.write_text('<testsuite tests="1" failures="1"/>', encoding='utf-8')
                self.assertEqual(ci_gate.run_constraint_parity(), 1)
                path.write_text('<testsuite tests="1" skipped="1"/>', encoding='utf-8')
                self.assertEqual(ci_gate.run_constraint_parity(), 1)
                path.write_text('invalid xml', encoding='utf-8')
                self.assertEqual(ci_gate.run_constraint_parity(), 2)


if __name__ == '__main__':
    unittest.main()
