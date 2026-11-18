"""Verify public iOS assets, then update the stable feed and optional legacy alias."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.request


REPO = 'aron7awol/OpenCapture'
BASE = f'https://github.com/{REPO}/releases/'
FEED = BASE + 'download/ios-updates/altstore.json'
LEGACY = BASE + 'latest/download/altstore.json'
MAX_FEED = 1024 * 1024


def gh(*args):
    return subprocess.check_output(['gh', *args], text=True).strip()


def read_public(url, limit):
    request = urllib.request.Request(url, headers={'Cache-Control': 'no-cache'})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Public asset exceeds its allowed size')
    return data


def describe(data):
    if len(data) > MAX_FEED:
        raise ValueError('Feed is too large')
    source = json.loads(data)
    if len(source['apps']) != 1:
        raise ValueError('Expected one OpenCapture app')
    app = source['apps'][0]
    if app['bundleIdentifier'] != 'io.github.aron7awol.OpenCapture':
        raise ValueError('Unexpected bundle identifier')
    version = app['versions'][0]
    if not re.fullmatch(r'\d+\.\d+\.\d+', version['version']):
        raise ValueError('Expected three-part app version')
    build = version['buildVersion']
    if not isinstance(build, str) or not re.fullmatch(r'[1-9]\d*', build):
        raise ValueError('Invalid iOS build')
    tags = (f"v{version['version']}-b{build}", f"v{version['version']}-ios.{build}")
    tag = next((t for t in tags if version['downloadURL'] == BASE + f'download/{t}/OpenCapture.ipa'), None)
    if tag is None or app['iconURL'] != BASE + f'download/{tag}/icon.png':
        raise ValueError('Unexpected immutable iOS asset URL')
    if type(version['size']) is not int or not 0 < version['size'] <= 512 * 1024 * 1024:
        raise ValueError('Invalid IPA size')
    if not re.fullmatch(r'[0-9a-f]{64}', version['sha256']):
        raise ValueError('Invalid IPA hash')
    return tuple(map(int, version['version'].split('.'))) + (int(build),), tag, version, app['iconURL']


def check_previous(previous, candidate):
    old, new = describe(previous), describe(candidate)
    if old[0] > new[0]:
        raise ValueError('Refusing an iOS feed downgrade')
    if old[0] == new[0] and previous != candidate:
        raise ValueError('Refusing changed metadata for the same iOS version/build')


def verify_visible(url, expected):
    for attempt in range(6):
        if read_public(url, MAX_FEED) == expected:
            return
        if attempt < 5:
            time.sleep(5)
    raise ValueError('Public feed is stale or different; publication requires investigation')


def publish(path, *, write=False, promote=None):
    data = path.read_bytes()
    _, tag, version, icon = describe(data)
    if path.name != 'altstore.json':
        raise ValueError('Feed asset must be named altstore.json')
    if promote is not None and promote != tag:
        raise ValueError('Latest promotion must match the verified IPA release')
    ipa = read_public(version['downloadURL'], version['size'])
    if len(ipa) != version['size'] or hashlib.sha256(ipa).hexdigest() != version['sha256']:
        raise ValueError('Public IPA integrity mismatch; feeds unchanged')
    if not read_public(icon, MAX_FEED).startswith(b'\x89PNG\r\n\x1a\n'):
        raise ValueError('Invalid public app icon')
    # Existing users still follow /latest. Never move that alias to an older app.
    legacy = read_public(LEGACY, MAX_FEED)
    check_previous(legacy, data)
    releases = json.loads(gh('api', '--paginate', '--slurp', f'repos/{REPO}/releases?per_page=100'))
    exists = any(r['tag_name'] == 'ios-updates' for page in releases for r in page)
    before = read_public(FEED, MAX_FEED) if exists else None
    if before is not None:
        check_previous(before, data)
    if not write:
        print('Verified public IPA and feed migration; nothing changed')
        return
    if exists:
        if read_public(FEED, MAX_FEED) != before:
            raise ValueError('iOS feed changed concurrently; refusing replacement')
        gh('release', 'upload', 'ios-updates', str(path), '--repo', REPO, '--clobber')
    else:
        commit = gh('api', f'repos/{REPO}/commits/{tag}', '--jq', '.sha')
        if not re.fullmatch(r'[0-9a-f]{40}', commit):
            raise ValueError('Cannot resolve the existing iOS release commit')
        gh('release', 'create', 'ios-updates', str(path), '--repo', REPO,
           '--target', commit, '--latest=false', '--prerelease', '--title', 'OpenCapture iOS update feed',
           '--notes', 'Stable AltStore source. The feed points to immutable versioned iOS releases. Existing latest/download/altstore.json subscriptions remain supported.')
    verify_visible(FEED, data)
    if promote:
        # The versioned release must carry the same feed before it becomes Latest.
        if read_public(BASE + f'download/{tag}/altstore.json', MAX_FEED) != data:
            raise ValueError('Versioned feed mismatch; legacy alias unchanged')
        if read_public(LEGACY, MAX_FEED) != legacy:
            raise ValueError('Legacy feed changed concurrently; refusing Latest promotion')
        gh('release', 'edit', tag, '--repo', REPO, '--latest')
        verify_visible(LEGACY, data)
    elif read_public(LEGACY, MAX_FEED) != legacy:
        raise ValueError('Legacy feed changed during migration')
    print('Verified stable iOS feed and legacy compatibility')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--feed', required=True, type=Path)
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--promote', help='After stable-feed verification, make this iOS release Latest for old clients')
    args = parser.parse_args()
    publish(args.feed, write=args.publish, promote=args.promote)
