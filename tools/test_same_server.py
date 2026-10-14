#!/usr/bin/env python3
"""Offline tests: Cloudflare URL validation (does not connect to a host)."""
from importlib import util
from pathlib import Path

spec = util.spec_from_file_location('kali_remote', Path(__file__).with_name('kali_remote.py'))
module = util.module_from_spec(spec)
spec.loader.exec_module(module)

cases = {
    'https://REMOTE.EXAMPLE.WORKERS.DEV/': 'https://remote.example.workers.dev',
    'https://remote.example.workers.dev': 'https://remote.example.workers.dev',
}
for url, expected in cases.items():
    assert module.normalize_server_url(url) == expected
for value in ['', 'http://remote.example.workers.dev', 'https://remote.example.workers.dev/path',
              'https://someone:secret@remote.example.workers.dev',
              'https://remote.example.workers.dev/?x=y']:
    try:
        module.normalize_server_url(value)
    except RuntimeError:
        pass
    else:
        raise AssertionError(f'Unsafe URL accepted: {value!r}')

print('PASS: Cloudflare URL matching and input validation')
