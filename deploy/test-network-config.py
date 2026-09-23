#!/usr/bin/env python3
"""Render only: verifies network wiring without contacting Docker daemon or starting services."""
import json
import os
from pathlib import Path
import subprocess

compose = Path(__file__).with_name('docker-compose.yml')
default_env = {k: os.environ[k] for k in ('PATH', 'HOME', 'DOCKER_CONFIG') if k in os.environ}
default_config = subprocess.run(['docker', 'compose', '--env-file', '/dev/null', '-f', str(compose),
    '--profile', 'light', 'config', '--format', 'json'], env=default_env,
    check=True, capture_output=True, text=True)
default_services = json.loads(default_config.stdout)['services']
assert default_services['postgres']['environment']['POSTGRES_PASSWORD'] == 'postgres'
assert default_services['facilitator']['environment']['DB_PASSWORD'] == 'postgres'
print('light: shared PostgreSQL password default agrees')

for network, magic in [('preprod', 1), ('preview', 2), ('mainnet', 764824073)]:
    env = {k: os.environ[k] for k in ('PATH', 'HOME', 'DOCKER_CONFIG') if k in os.environ}
    env['CARDANO_NETWORK'] = network
    env.update({
        'POSTGRES_ADMIN_PASSWORD': 'test-admin-only',
    })
    result = subprocess.run(['docker', 'compose', '--env-file', '/dev/null', '-f', str(compose),
        '--profile', 'light', '--profile', 'full', '--profile', 'yano', 'config', '--format', 'json'],
        env=env, check=True, capture_output=True, text=True)
    services = json.loads(result.stdout)['services']
    for name in ('facilitator', 'facilitator-node', 'facilitator-yano'):
        assert services[name]['environment'].get('X402_NETWORK_ID') == 'cardano:' + network, (network, name)
        assert services[name]['environment']['DB_USER'] == 'postgres'
        assert services[name]['environment']['DB_PASSWORD'] == 'test-admin-only'
        assert 'FACILITATOR_API_KEY' not in services[name]['environment']
        assert 'FACILITATOR_RATE_LIMIT_RPM' not in services[name]['environment']
        assert services[name]['ports'][0]['host_ip'] == '127.0.0.1'
    assert 'ports' not in services['postgres']
    assert services['postgres']['environment']['POSTGRES_HOST_AUTH_METHOD'] == 'scram-sha-256'
    assert services['postgres']['environment']['POSTGRES_INITDB_ARGS'] == '--auth-host=scram-sha-256 --auth-local=trust'
    assert services['yaci-store']['environment']['SPRING_DATASOURCE_USERNAME'] == 'postgres'
    assert services['yaci-store']['environment']['SPRING_DATASOURCE_PASSWORD'] == 'test-admin-only'
    assert services['yaci-store']['environment']['SPRING_FLYWAY_SCHEMAS'] == 'yaci_store'
    assert services['postgres']['environment']['POSTGRES_PASSWORD'] == 'test-admin-only'
    assert len(services['postgres']['volumes']) == 1
    assert services['facilitator']['environment']['BLOCKFROST_BASE_URL'] == f'https://cardano-{network}.blockfrost.io/api/v0'
    assert services['cardano-node']['environment']['NETWORK'] == network
    assert services['yano']['environment']['YANO_NETWORK'] == network
    assert services['yano']['environment']['YANO_PROFILE'] == network
    assert int(services['yaci-store']['environment']['STORE_CARDANO_PROTOCOL_MAGIC']) == magic
    if network != 'preprod':
        assert str(services['yaci-store']['environment'].get('STORE_CARDANO_SYNC_START_SLOT', '0')) == '0'
        assert services['yaci-store']['environment'].get('STORE_CARDANO_SYNC_START_BLOCKHASH', '') == ''
    print(f'{network}: facilitator IDs, hosted URL, node, indexer magic and Yano agree')
