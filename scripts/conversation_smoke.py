#!/usr/bin/env python3
"""HTTP integration checks for conversation cards and production artifacts."""
import argparse
import copy
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:5178/api')
    parser.add_argument('--report')
    parser.add_argument('--timeout', type=int, default=330)
    args = parser.parse_args()
    base = args.base_url.rstrip('/')
    checks = []

    def call(method, path, body=None, token=None, expected=(200, 201, 202, 204), key=None, raw=False):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if key:
            headers['Idempotency-Key'] = key
        req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=40)
        except urllib.error.HTTPError as exc:
            response = exc
        payload = response.read()
        assert response.status in expected, (method, path, response.status, payload.decode(errors='replace')[:600])
        if raw:
            return payload.decode('utf-8')
        return json.loads(payload) if payload else None

    def check(name):
        checks.append(name)
        print('PASS', name, flush=True)

    def get(cid, token):
        return call('GET', '/conversations/' + cid, token=token)

    def wait(cid, token):
        until = time.monotonic() + args.timeout
        while time.monotonic() < until:
            c = get(cid, token)
            if c['status'] == 'FAILED':
                messages = [e for e in c['events'] if e['type'] == 'run.failed']
                raise AssertionError(('conversation failed', messages[-1:] or c['messages'][-1:]))
            if not c.get('activeRunId') and c['status'] != 'RUNNING':
                return c
            time.sleep(0.4)
        raise AssertionError('conversation timeout')

    def send(cid, token, body, key=None):
        response = call('POST', '/conversations/' + cid + '/messages', body, token, key=key)
        return wait(cid, token), response

    def group(c, kind):
        for m in reversed(c['messages']):
            for g in m.get('choices', []):
                if g['kind'] == kind and g['status'] == 'OPEN':
                    return m, g
        raise AssertionError('Missing choice group: ' + kind)

    session = call('POST', '/auth/login', {'username': os.environ.get('SMOKE_ADMIN_USERNAME', 'admin'), 'password': os.environ['SMOKE_ADMIN_PASSWORD']})
    token = session['token']
    suffix = uuid.uuid4().hex[:10]
    other = call('POST', '/auth/register', {'username': 'chatqa_' + suffix, 'password': 'Qa-' + uuid.uuid4().hex, 'nickname': '会话测试'})
    stranger = other['token']
    c = call('POST', '/conversations', {'title': '对话验收_' + suffix}, token)
    cid = c['id']
    assert not c['messages'] and c['artifact'] is None
    call('GET', '/conversations/' + cid, token=stranger, expected=(403, 404))
    call('GET', '/conversations/' + cid + '/events', token=stranger, expected=(403, 404))
    call('POST', '/conversations/' + cid + '/messages', {'content': '测试'}, stranger, expected=(403, 404))
    check('持久会话与消息、事件权限隔离')
    c, first = send(cid, token, {'content': '我想做校园生活短视频，先给我三个选题，暂时不要生成作品。'}, key='first-' + suffix)
    assert c['artifact'] is None
    m, g = group(c, 'TOPIC')
    assert [o['label'] for o in g['options']] == ['A', 'B', 'C']
    assert len({o['id'] for o in g['options']}) == 3
    replay = call('POST', '/conversations/' + cid + '/messages', {'content': '我想做校园生活短视频，先给我三个选题，暂时不要生成作品。'}, token, key='first-' + suffix)
    assert first['runId'] == replay['runId']
    check('讨论选题返回 ABC、没有过早生成作品、重复提交去重')
    selection = {'messageId': m['id'], 'groupId': g['id'], 'optionId': g['options'][0]['id']}
    call('POST', '/conversations/' + cid + '/messages', {'choice': {**selection, 'optionId': str(uuid.uuid4())}}, token, expected=(400, 404, 409))
    c, _ = send(cid, token, {'choice': selection})
    assert c['brief']['topic'] == g['options'][0]['value']
    call('POST', '/conversations/' + cid + '/messages', {'choice': selection}, token, expected=(400, 409))
    check('点击 A 更新需求、伪造和重复选择被拒绝')
    c, _ = send(cid, token, {'content': '先不要生成作品，给我三个内容方向供选择。'})
    m, g = group(c, 'DIRECTION')
    c, _ = send(cid, token, {'choice': {'messageId': m['id'], 'groupId': g['id'], 'customText': '轻松情境演绎，用宿舍生活实例展开'}})
    assert c['brief']['direction'] == '轻松情境演绎，用宿舍生活实例展开'
    check('内容方向卡片与 D 自定义需求持久化')
    c, _ = send(cid, token, {'content': '目标观众给我三个选项，仍然先讨论。'})
    m, g = group(c, 'AUDIENCE')
    c, _ = send(cid, token, {'choice': {'messageId': m['id'], 'groupId': g['id'], 'optionId': g['options'][1]['id']}})
    assert c['brief']['audience'] == g['options'][1]['value'] and c['artifact'] is None
    check('目标观众卡片 B 选择与讨论边界')
    c, _ = send(cid, token, {'content': '按已确定需求，直接制作一份60秒完整方案，包括脚本、分镜、拍摄、配音和剪辑。'})
    artifact = c['artifact']
    assert artifact and artifact['content']['segments']
    production = artifact['content']
    for field in ('shootingPlan', 'voiceoverPlan', 'editingPlan'):
        assert production[field], field
    ids = [s['id'] for s in production['segments']]
    assert len(ids) == len(set(ids))
    events = call('GET', '/conversations/' + cid + '/events?after=0', token=token)
    event_ids = [e['id'] for e in events]
    assert event_ids == sorted(set(event_ids))
    assert any(e['type'] == 'tool.started' for e in events) and any(e['type'] == 'tool.completed' for e in events)
    assert call('GET', '/conversations/' + cid + '/events?after=' + str(event_ids[-1]), token=token) == []
    check('完整制作包、稳定分镜 ID、持久工具事件与续接序号')
    req = urllib.request.Request(base + '/conversations/' + cid + '/stream?after=0', headers={'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(req, timeout=5) as response:
        assert 'text/event-stream' in response.headers.get('Content-Type', '')
        data = ''
        for _ in range(12):
            data += response.readline().decode()
            if 'data:' in data:
                break
        assert 'data:' in data and 'run-event' in data
    check('Bearer SSE 持久事件重放')
    original = copy.deepcopy(production)
    edited = copy.deepcopy(original)
    edited['opening'] = '手动保存的开场_' + suffix
    current_version = artifact['versionNumber']
    c = call('PUT', '/conversations/' + cid + '/artifact', {'content': edited, 'baseVersion': current_version}, token)
    call('PUT', '/conversations/' + cid + '/artifact', {'content': edited, 'baseVersion': current_version}, token, expected=(409,))
    manual_id = c['versions'][0]['id']
    manual = next(v for v in c['versions'] if v['content']['opening'] == edited['opening'])
    manual_id = manual['id']
    c, _ = send(cid, token, {'content': '只把开场改得更直接，其他内容完全保留。'})
    modified = c['artifact']['content']
    assert modified['opening'] != edited['opening']
    for field in edited:
        if field != 'opening':
            assert modified[field] == edited[field], ('unexpected revision', field)
    check('手动编辑、乐观版本控制、对话局部修改保持其他区块')
    c = call('POST', '/conversations/' + cid + '/artifact/restore/' + manual_id, {}, token)
    assert c['artifact']['content']['opening'] == edited['opening']
    c = call('POST', '/conversations/' + cid + '/artifact/finalize', {'baseVersion': c['artifact']['versionNumber']}, token)
    assert c['artifact']['status'] == 'FINAL'
    exported = call('GET', '/conversations/' + cid + '/artifact/export?format=markdown', token=token, raw=True)
    assert all(text in exported for text in ('拍摄', '配音', '剪辑', edited['opening']))
    check('版本恢复、定稿与完整制作包导出')
    call('PATCH', '/conversations/' + cid, {'title': '已验收会话_' + suffix}, token)
    assert get(cid, token)['title'] == '已验收会话_' + suffix
    assert any(item['id'] == cid for item in call('GET', '/conversations', token=token))
    check('历史记录、刷新后内容与会话重命名')
    result = {'passed': len(checks), 'checks': checks, 'conversationId': cid,
              'note': '演示模式只验证业务链路，不代表真实模型质量。'}
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
