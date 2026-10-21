"""Validate a proposed user-pack directory without activating it."""
import argparse
import json
from pathlib import Path
from app.services.user_style_packs import discover


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    args=parser.parse_args()
    if not args.directory.is_dir():
        parser.error('directory does not exist')
    assets,reports=discover(args.directory)
    print(json.dumps({'packs':reports,'overlay_assets':len(assets)},ensure_ascii=False,indent=2))
    return int(any(not report['accepted'] for report in reports))


if __name__=='__main__':
    raise SystemExit(main())
