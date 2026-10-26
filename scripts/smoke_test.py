#!/usr/bin/env python3
"""End-to-end HTTP checks against a running stack. Creates isolated test records.
Credentials read only from environment. Uses explicit demo fixture if requested.
"""
import argparse
import copy
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://localhost:8088/api')
    parser.add_argument('--timeout', type=int, default=300)
    parser.add_argument('--report', help='Write a JSON validation report (no credentials)')
    args = parser.parse_args()
    base = args.base_url.rstrip('/')
    checks = []

    def call(method, path, body=None, token=None, expected=(200, 201, 204), raw=False, extra_headers=None):
        headers = dict(extra_headers or {})
        if token:
            headers['Authorization'] = 'Bearer ' + token
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode()
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=40)
        except urllib.error.HTTPError as exc:
            response = exc
        payload = response.read()
        assert response.status in expected, (method, path, response.status, payload.decode(errors='replace')[:500])
        if raw:
            return payload.decode('utf-8-sig')
        return json.loads(payload) if payload else None

    def upload(filename, text, token, category, expected=(200, 201)):
        boundary = 'qa-' + uuid.uuid4().hex
        parts = []
        for name, value in [('category', category), ('sourceUrl', 'https://example.com/upload-fixture')]:
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: text/plain\r\n\r\n'.encode())
        parts.append(text.encode())
        parts.append(f'\r\n--{boundary}--\r\n'.encode())
        req = urllib.request.Request(base + '/admin/knowledge', data=b''.join(parts), headers={
            'Authorization': 'Bearer ' + token, 'Content-Type': 'multipart/form-data; boundary=' + boundary}, method='POST')
        try:
            response = urllib.request.urlopen(req, timeout=40)
        except urllib.error.HTTPError as exc:
            response = exc
        payload = response.read()
        assert response.status in expected, (response.status, payload.decode(errors='replace')[:500])
        return json.loads(payload) if payload else None

    def check(name):
        checks.append(name)
        print('PASS', name, flush=True)

    def wait_task(task_id, token, target):
        end = time.monotonic() + args.timeout
        while time.monotonic() < end:
            task = call('GET', '/tasks/' + task_id, token=token)
            if task['status'] == 'FAILED':
                raise AssertionError('Task failed: ' + str(task.get('error')))
            if task['status'] in target:
                return task
            time.sleep(1)
        raise AssertionError('Task timeout: ' + task_id)

    def wait_doc(doc_id, token):
        end = time.monotonic() + args.timeout
        while time.monotonic() < end:
            doc = call('GET', '/admin/knowledge/' + doc_id, token=token)
            if doc['status'] == 'FAILED':
                raise AssertionError('Index failed: ' + str(doc.get('error')))
            if doc['status'] == 'READY':
                return doc
            time.sleep(1)
        raise AssertionError('Index timeout')

    admin = call('POST', '/auth/login', {'username': os.environ.get('SMOKE_ADMIN_USERNAME', 'admin'),
                                        'password': os.environ['SMOKE_ADMIN_PASSWORD']})
    at = admin['token']
    assert admin['user']['role'] == 'ADMIN'
    check('管理员登录')
    stamp = uuid.uuid4().hex[:10]
    password = 'Test-' + uuid.uuid4().hex
    user = call('POST', '/auth/register', {'username': 'qa_' + stamp, 'password': password, 'nickname': '联调用户'})
    ut = user['token']
    user2 = call('POST', '/auth/register', {'username': 'qb_' + stamp, 'password': password, 'nickname': '隔离用户'})
    vt = user2['token']
    assert call('GET', '/auth/me', token=ut)['id'] == user['user']['id']
    call('GET', '/admin/users', token=ut, expected=(403,))
    check('注册、身份与管理员权限隔离')
    call('PUT', '/auth/me', {'nickname': '创作测试'}, ut)
    assert call('GET', '/auth/me', token=ut)['nickname'] == '创作测试'
    check('个人信息保存')
    category = '联调_' + stamp
    doc = call('POST', '/admin/knowledge/text', {
        'name': '反诈骗创作方法_' + stamp,
        'text': '反诈骗短视频创作参考：大学生接到陌生转账请求，应通过官方渠道核实身份，保护个人验证码。'
                '本段为人工编写的系统联调演示资料，不应被视为正式政策。'
                '短视频口播应采用开场提出问题、主体给出建议、结尾总结的结构。',
        'category': category, 'sourceUrl': 'https://example.com/qa-fixture'
    }, at)
    did = doc['id']
    doc = wait_doc(did, at)
    assert doc['chunkCount'] > 0 and doc.get('chunks')
    hits = call('POST', '/admin/knowledge/search', {'query': '大学生反诈骗短视频', 'category': category, 'limit': 5}, at)
    assert any(s['documentId'] == did for s in hits), hits
    check('知识导入、切分、向量索引与实际检索')
    call('PUT', '/admin/knowledge/' + did, {'enabled': False, 'category': category, 'sourceUrl': 'https://example.com/qa-fixture'}, at)
    hits = call('POST', '/admin/knowledge/search', {'query': '大学生反诈骗短视频', 'category': category}, at)
    assert not any(s['documentId'] == did for s in hits)
    call('PUT', '/admin/knowledge/' + did, {'enabled': True, 'category': category, 'sourceUrl': 'https://example.com/qa-fixture'}, at)
    check('知识停用与重新启用同步检索')
    uploaded = upload('qa-fixture.md', '# 创作示例\n使用具体场景组织短视频内容，开场明确问题，主体给出可操作建议。仅用于联调测试。', at, category)
    uploaded = wait_doc(uploaded['id'], at)
    assert uploaded['chunks'] and '具体场景' in uploaded['chunks'][0]['text']
    call('DELETE', '/admin/knowledge/' + uploaded['id'], token=at)
    upload('not-allowed.exe', 'not executable: QA invalid format fixture', at, category, expected=(400,))
    check('实际文件上传、异步解析与不支持格式拒绝')
    params = {'title': '联调创作_' + stamp, 'topic': '大学生反诈骗', 'audience': '大学生', 'platform': '抖音',
              'style': '轻松科普', 'duration': 60, 'requirements': '清楚易懂，参考资料支持的建议',
              'mode': 'GUIDED', 'category': category, 'ragEnabled': True}
    task = call('POST', '/tasks', params, ut)
    tid = task['id']
    call('GET', '/tasks/' + tid, token=vt, expected=(403, 404))
    call('GET', '/tasks/' + tid + '/export', token=vt, expected=(403, 404, 409))
    call('DELETE', '/tasks/' + tid, token=vt, expected=(403, 404))
    check('任务、导出、删除接口所有者隔离')
    task = wait_task(tid, ut, {'WAITING_SELECTION'})
    assert len(task['topics']) >= 2
    assert task.get('events')
    call('POST', '/tasks/' + tid + '/select-topic', {'topicIndex': 0}, ut)
    task = wait_task(tid, ut, {'COMPLETED'})
    assert task['result']['segments'] and task['versions']
    assert task['sources'], 'RAG generation should retain source snapshots'
    check('引导模式选题、确认、脚本与引用保存')
    original = copy.deepcopy(task['result'])
    edited = copy.deepcopy(original)
    edited['opening'] = '这是手动编辑的开场_' + stamp
    task = call('PUT', '/tasks/' + tid + '/content', {'content': edited}, ut)
    assert task['result']['opening'] == edited['opening']
    manual = next(v for v in task['versions'] if v['content']['opening'] == edited['opening'])
    call('POST', '/tasks/' + tid + '/revise', {'instruction': '开场缩短到一句话，语气更自然', 'section': 'opening'}, ut)
    task = wait_task(tid, ut, {'COMPLETED'})
    assert task['result']['opening'] != edited['opening']
    for field in edited:
        if field != 'opening':
            assert task['result'][field] == edited[field], ('Partial revision changed', field)
    check('手动编辑、AI 局部修改、非目标字段保留')
    task = call('POST', '/tasks/' + tid + '/restore/' + manual['id'], {}, ut)
    assert task['result']['opening'] == edited['opening']
    assert len(task['versions']) >= 4
    exported = call('GET', '/tasks/' + tid + '/export?format=markdown', token=ut, raw=True)
    assert edited['opening'] in exported and len(exported) > 100
    check('不可变版本、恢复与文件导出')
    params.update(title='快速创作_' + stamp, mode='QUICK', ragEnabled=False)
    idem_headers = {'Idempotency-Key': 'qa-' + stamp}
    quick = call('POST', '/tasks', params, ut, extra_headers=idem_headers)
    duplicate = call('POST', '/tasks', params, ut, extra_headers=idem_headers)
    assert quick['id'] == duplicate['id']
    check('相同请求标识重复提交只产生一个任务')
    quick = wait_task(quick['id'], ut, {'COMPLETED'})
    assert quick['result']['segments']
    check('快速创作完整流程')
    assert call('GET', '/dashboard', token=ut)['totalTasks'] >= 2
    assert len(call('GET', '/tasks?search=' + urllib.parse.quote(stamp), token=ut)) >= 2
    check('首页统计与历史任务搜索')
    call('POST', '/admin/knowledge/' + did + '/reindex', {}, at)
    wait_doc(did, at)
    call('DELETE', '/admin/knowledge/' + did, token=at)
    hits = call('POST', '/admin/knowledge/search', {'query': '大学生反诈骗短视频', 'category': category}, at)
    assert not any(s['documentId'] == did for s in hits)
    historical = call('GET', '/tasks/' + tid, token=ut)
    assert historical['sources'], 'Historical source snapshot lost after document deletion'
    check('重新索引、删除、历史引用快照保留')
    call('PUT', '/admin/users/' + user2['user']['id'], {'enabled': False, 'role': 'USER'}, at)
    call('GET', '/auth/me', token=vt, expected=(401, 403))
    call('PUT', '/admin/users/' + admin['user']['id'], {'enabled': False, 'role': 'ADMIN'}, at, expected=(400, 409))
    check('禁用用户立即失效与管理员自我保护')
    stats = call('GET', '/admin/stats', token=at)
    health = call('GET', '/admin/health', token=at)
    assert stats['tasks'] >= 2 and health.get('ai')
    check('管理统计与服务健康检查')
    call('POST', '/auth/logout', {}, ut)
    call('GET', '/auth/me', token=ut, expected=(401,))
    check('退出登录撤销 Redis 会话')
    session = call('POST', '/auth/login', {'username': user['user']['username'], 'password': password})
    changed_password = 'New-' + uuid.uuid4().hex
    call('PUT', '/auth/password', {'oldPassword': password, 'newPassword': changed_password}, session['token'])
    call('GET', '/auth/me', token=session['token'], expected=(401,))
    call('POST', '/auth/login', {'username': user['user']['username'], 'password': password}, expected=(401,))
    renewed = call('POST', '/auth/login', {'username': user['user']['username'], 'password': changed_password})
    assert renewed['token'] != session['token']
    call('POST', '/auth/logout', {}, renewed['token'])
    check('修改密码撤销旧会话与新密码登录')
    report = {'passed': len(checks), 'checks': checks, 'createdTaskIds': [tid, quick['id']],
                      'note': '联调记录保留以供检查；演示模式不证明真实模型质量。'}
    if args.report:
        from pathlib import Path
        report_path = Path(args.report)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
