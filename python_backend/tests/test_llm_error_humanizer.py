"""LLM billing/auth error humanization."""

import unittest


class TestLlmErrorHumanizer(unittest.TestCase):
    def test_anthropic_credit_balance(self):
        from app.services.llm_error_humanizer import summarize_billing_or_auth_error

        msg = summarize_billing_or_auth_error(
            "Error code: 400 - Your credit balance is too low to access the Anthropic API."
        )
        self.assertIsNotNone(msg)
        self.assertIn("Anthropic", msg)
        self.assertIn("余额不足", msg)

    def test_403_forbidden(self):
        from app.services.llm_error_humanizer import summarize_billing_or_auth_error

        msg = summarize_billing_or_auth_error("Error code: 403 permission denied")
        self.assertIsNotNone(msg)
        self.assertIn("403", msg)

    def test_humanize_wraps_with_provider(self):
        from app.services.llm_error_humanizer import humanize_llm_exception
        from unittest.mock import Mock

        req = Mock()
        req.llmProvider = "anthropic"
        req.model = "claude-3-5-sonnet-latest"
        out = humanize_llm_exception(
            RuntimeError("credit balance is too low to access the Anthropic API"),
            req,
        )
        self.assertIn("LLM 调用失败", out)
        self.assertIn("anthropic/claude-3-5-sonnet-latest", out)
        self.assertIn("建议", out)


if __name__ == "__main__":
    unittest.main()
