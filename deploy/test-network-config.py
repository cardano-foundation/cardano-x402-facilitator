#!/usr/bin/env python3
"""Render only: verifies network wiring without contacting Docker daemon or starting services."""
import json
import os
from pathlib import Path
import subprocess

compose = Path(__file__).with_name('docker-compose.yml')
for network, magic in [('preprod', 1), ('preview', 2), ('mainnet', 764824073)]:
    env = {k: os.environ[k] for k in ('PATH', 'HOME', 'DOCKER_CONFIG') if k in os.environ}
    env['CARDANO_NETWORK'] = network
    result = subprocess.run(['docker', 'compose', '--env-file', '/dev/null', '-f', str(compose),
        '--profile', 'light', '--profile', 'full', '--profile', 'yano', 'config', '--format', 'json'],
        env=env, check=True, capture_output=True, text=True)
    services = json.loads(result.stdout)['services']
    for name in ('facilitator', 'facilitator-node', 'facilitator-yano'):
        assert services[name]['environment'].get('X402_NETWORK_ID') == 'cardano:' + network, (network, name)
    assert services['facilitator']['environment']['BLOCKFROST_BASE_URL'] == f'https://cardano-{network}.blockfrost.io/api/v0'
    assert services['cardano-node']['environment']['NETWORK'] == network
    assert services['yano']['environment']['YANO_NETWORK'] == network
    assert services['yano']['environment']['YANO_PROFILE'] == network
    assert int(services['yaci-store']['environment']['STORE_CARDANO_PROTOCOL_MAGIC']) == magic
    if network != 'preprod':
        assert str(services['yaci-store']['environment'].get('STORE_CARDANO_SYNC_START_SLOT', '0')) == '0'
    print(f'{network}: facilitator IDs, hosted URL, node, indexer magic and Yano agree')
