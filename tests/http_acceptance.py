"""对专用测试实例执行端到端验收。Python 3.10+，只使用标准库。

设置 WORKORDER_TEST_URL、WORKORDER_TEST_ADMIN、WORKORDER_TEST_PASSWORD 后运行此文件。
如果管理员尚须首次改密，还需设置 WORKORDER_TEST_NEW_PASSWORD。
每次创建独立的测试账号、设备和工单，保留工单以便核对历史。
"""

from concurrent.futures import ThreadPoolExecutor
from http.cookiejar import CookieJar
import json
import os
import secrets
from threading import Barrier
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener
from uuid import uuid4


class Session:
    def __init__(self, url):
        self.url = url.rstrip("/") + "/api"
        self.client = build_opener(HTTPCookieProcessor(CookieJar()))
        self.token = None

    def request(self, method, path, body=None, *, status=200, code=None,
                key=None, csrf=True):
        headers = {"Accept": "application/json"}
        if key:
            headers["Idempotency-Key"] = str(key)
        if method != "GET" and csrf:
            if self.token is None:
                self.token = self.request("GET", "/auth/csrf")["token"]
            headers["X-CSRF-TOKEN"] = self.token
        encoded = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = Request(self.url + path, data=encoded, headers=headers, method=method)
        try:
            response = self.client.open(req, timeout=20)
        except HTTPError as error:
            response = error
        with response:
            raw = response.read()
            actual = response.status
            try:
                content = json.loads(raw) if raw else None
            except (ValueError, UnicodeDecodeError) as error:
                raise AssertionError(f"{method} {path}: HTTP {actual} returned non-JSON content") from error
        statuses = (status,) if isinstance(status, int) else status
        machine_code = content.get("code") if isinstance(content, dict) else None
        if actual not in statuses or (code and machine_code != code):
            raise AssertionError(
                f"{method} {path}: expected {status} {code or ''}, received {actual} {machine_code or ''}"
            )
        if len(statuses) == 1:
            return content
        return actual, content

    def login(self, name, password):
        self.token = None
        user = self.request("POST", "/auth/login", {"username": name, "password": password})
        self.token = None
        return user


def demand(condition, message):
    if not condition:
        raise AssertionError(message)


def new_password():
    return secrets.token_urlsafe(24)


def concurrent(first, second):
    barrier = Barrier(2, timeout=10)

    def synchronized(action):
        barrier.wait()
        return action()

    with ThreadPoolExecutor(max_workers=2) as pool:
        pending = [pool.submit(synchronized, f) for f in (first, second)]
        return [future.result() for future in pending]


def register(base, admin, login, role):
    temporary = new_password()
    permanent = new_password()
    created = admin.request("POST", "/users", {
        "username": login, "role": role, "displayName": login, "password": temporary,
    }, status=201)
    demand(created["mustChangePassword"], "新账号未要求改密")
    browser = Session(base)
    user = browser.login(login, temporary)
    demand(user["mustChangePassword"], "登录身份未反映强制改密")
    browser.request("GET", "/equipment", status=403, code="PASSWORD_CHANGE_REQUIRED")
    browser.request("PUT", "/auth/password", {
        "currentPassword": temporary, "newPassword": permanent,
    }, status=204)
    browser.request("GET", "/auth/me", status=401, code="AUTH_REQUIRED")
    user = browser.login(login, permanent)
    demand(user["role"] == role and not user["mustChangePassword"], "改密后身份不正确")
    return browser, created["id"], permanent


def verify_summary(detail):
    order = detail["order"]
    summaries = detail["summaries"]
    demand(set(summaries) == {"TFIDF", "TEXTRANK"}, "缺少算法结果")
    for algorithm, summary in summaries.items():
        demand(summary["algorithm"] == algorithm, "算法标识不一致")
        demand(summary["textVersion"] == order["textVersion"], "摘要原文版本不一致")
        for field, source in (("fault", "faultText"), ("action", "actionText")):
            raw = order[source]
            result = summary[field]
            if raw == "":
                demand(result == {"sentence": None, "start": None, "end": None,
                                  "keywords": [], "selection": "EMPTY"}, "空栏格式不符")
                continue
            encoded = raw.encode("utf-16-le")
            start, end = result["start"], result["end"]
            demand(0 <= start < end <= len(encoded) // 2, "原句区间越界")
            selected = encoded[start * 2:end * 2].decode("utf-16-le")
            demand(selected == result["sentence"], "原句定位不符")
            demand(len(result["keywords"]) <= 5, "关键词超出展示上限")


def run():
    base = os.environ["WORKORDER_TEST_URL"]
    admin_name = os.environ["WORKORDER_TEST_ADMIN"]
    admin_password = os.environ["WORKORDER_TEST_PASSWORD"]
    admin = Session(base)
    admin.request("GET", "/orders", status=401, code="AUTH_REQUIRED")
    if admin.login(admin_name, admin_password)["mustChangePassword"]:
        new = os.environ["WORKORDER_TEST_NEW_PASSWORD"]
        admin.request("PUT", "/auth/password", {
            "currentPassword": admin_password, "newPassword": new,
        }, status=204)
        admin.login(admin_name, new)
    admin.request("GET", "/orders", status=403, code="FORBIDDEN")

    tag = uuid4().hex[:10]
    boss, _, _ = register(base, admin, "qa_s_" + tag, "SUPERVISOR")
    name = "qa_t_" + tag
    author, author_id, author_pass = register(base, admin, name, "TECHNICIAN")
    other_name = "qa_b_" + tag
    colleague, colleague_id, colleague_pass = register(base, admin, other_name, "TECHNICIAN")
    admin.request("POST", "/users", {"username": name.upper(), "role": "TECHNICIAN",
                  "displayName": "同名测试", "password": new_password()},
                  status=409, code="USERNAME_EXISTS")
    boss.request("GET", "/users", status=403, code="FORBIDDEN")
    author.request("POST", "/equipment", {"name": "设备"}, status=403, code="FORBIDDEN")
    print("PASS 账号、首次改密、会话与角色")

    equipment = {"code": " q-" + tag + " ", "name": "测试循环泵", "location": "泵房"}
    boss.request("POST", "/equipment", equipment, csrf=False, status=403,
                 code="INVALID_CSRF_TOKEN")
    eq = boss.request("POST", "/equipment", equipment, status=201)
    eq_id = eq["id"]
    demand(eq["code"] == equipment["code"].strip().upper(), "设备编号未归一化")
    boss.request("POST", "/equipment", equipment, status=409, code="EQUIPMENT_CODE_EXISTS")
    print("PASS CSRF、设备维护、唯一性")

    payload = {"equipmentId": eq_id, "title": "循环泵试机" + tag,
               "faultText": "🔧 E17！\r\n复位后仍未启动。"}
    key = uuid4()
    twin = Session(base)
    twin.login(name, author_pass)
    results = concurrent(
        lambda: author.request("POST", "/orders", payload, key=key, status=(200, 201)),
        lambda: twin.request("POST", "/orders", payload, key=key, status=(200, 201)),
    )
    demand(sorted(r[0] for r in results) == [200, 201], "并发创建没有稳定重放")
    order_id = results[0][1]["id"]
    demand(results[1][1]["id"] == order_id, "同键创建了两张工单")
    path = "/orders/" + order_id
    an_event = path + "/transitions"
    author.request("POST", "/orders", {**payload, "title": "不同请求"}, key=key,
                   status=409, code="IDEMPOTENCY_KEY_REUSED")
    initial = author.request("GET", path)
    verify_summary(initial)
    demand(initial["order"]["creatorId"] == author_id, "创建人错误")
    demand(initial["order"]["faultText"] == payload["faultText"], "原文换行或补充平面字符发生变化")
    demand(len(initial["events"]) == 1 and initial["permissions"]["canStart"], "新工单权限或事件异常")
    demand(not any(colleague.request("GET", path)["permissions"].values()), "非本人可操作工单")
    colleague.request("PATCH", path, {"version": 1, "title": "变更"}, status=403, code="FORBIDDEN")
    boss.request("POST", an_event, {"version": 1, "action": "START"}, status=403, code="FORBIDDEN")
    author.request("POST", an_event, {"version": 1, "action": "COMPLETE"},
                   status=409, code="ORDER_STATE_CONFLICT")
    print("PASS 并发幂等创建、工单归属与空栏")

    renamed = boss.request("PATCH", "/equipment/" + eq_id, {"version": eq["version"],
                           "name": "测试回水泵", "location": None})
    demand(renamed["location"] is None, "设备位置未清空")
    eq_status = "/equipment/" + eq_id + "/status"
    boss.request("PUT", eq_status, {"version": eq["version"], "status": "DISABLED"},
                 status=409, code="VERSION_CONFLICT")
    boss.request("PUT", eq_status, {"version": renamed["version"], "status": "DISABLED"})
    author.request("POST", "/orders", payload, key=uuid4(), status=409, code="EQUIPMENT_DISABLED")
    author.request("POST", "/orders", payload, key=key, status=200)

    start = {"version": 1, "action": "START"}
    results = concurrent(
        lambda: author.request("POST", an_event, start, status=(200, 409)),
        lambda: twin.request("POST", an_event, start, status=(200, 409)),
    )
    demand(sorted(r[0] for r in results) == [200, 409], "双窗口状态流转未正确解决冲突")
    loser = next(r[1] for r in results if r[0] == 409)
    demand(loser["code"] == "VERSION_CONFLICT", "过期版本错误码不符")
    mid = author.request("GET", path)
    demand(mid["order"]["version"] == 2 and mid["order"]["textVersion"] == 1,
           "仅改状态时版本不符")
    demand(mid["summaries"] == initial["summaries"], "仅改状态时改变了摘要")
    invalid = author.request("POST", an_event, {"version": 2, "action": "COMPLETE"},
                             status=422, code="VALIDATION_FAILED")
    demand("actionText" in invalid["fieldErrors"], "未指出缺少措施")
    demand(author.request("GET", path) == mid, "失败完成操作变更了数据")

    action_text = "复位后仍未恢复。更换接触器后试机，电机恢复转动。"
    author.request("POST", an_event, {"version": 2, "action": "COMPLETE", "actionText": action_text})
    done = boss.request("GET", path)
    verify_summary(done)
    order = done["order"]
    demand((order["version"], order["textVersion"], order["status"]) == (3, 2, "COMPLETED"),
           "最终版本或状态不符")
    demand(order["equipmentName"] == equipment["name"], "历史设备快照已被覆盖")
    demand([e["type"] for e in done["events"]] == ["CREATED", "STARTED", "COMPLETED"],
           "事件顺序或数量不符")
    demand(not any(author.request("GET", path)["permissions"].values()), "已完工单仍可写")
    author.request("PATCH", path, {"version": 3, "title": "只读"}, status=409,
                   code="ORDER_STATE_CONFLICT")
    history = boss.request("GET", "/orders?" + urlencode({"equipmentId": eq_id,
                           "keyword": "e17", "status": "COMPLETED"}))
    demand(history["total"] == 1 and history["items"][0]["id"] == order_id, "历史查询失配")
    card = history["items"][0]["summary"]
    demand(all(card[f] == done["summaries"]["TFIDF"][f]["sentence"] for f in ("fault", "action")),
           "列表和详情摘要不一致")
    percent = boss.request("GET", "/orders?" + urlencode({"equipmentId": eq_id, "keyword": "%"}))
    demand(percent["total"] == 0, "通配符未按字面匹配")
    boss.request("GET", "/orders?dateFrom=2026-01-02&dateTo=2026-01-01", status=422,
                 code="VALIDATION_FAILED")
    end_page = boss.request("GET", "/orders?" + urlencode({"equipmentId": eq_id, "page": 2, "pageSize": 1}))
    demand(end_page == {"items": [], "page": 2, "pageSize": 1, "total": 1}, "末页外的分页口径不符")
    print("PASS 设备历史、双窗口版本冲突、流转与双算法原文定位")

    user = admin.request("GET", "/users?" + urlencode({"username": other_name}))["items"][0]
    status_path = "/users/" + colleague_id + "/status"
    disabled = admin.request("PUT", status_path, {"version": user["version"], "status": "DISABLED"})
    colleague.request("GET", "/auth/me", status=401, code="AUTH_REQUIRED")
    enabled = admin.request("PUT", status_path, {"version": disabled["version"], "status": "ACTIVE"})
    colleague.login(other_name, colleague_pass)
    temp = new_password()
    admin.request("POST", "/users/" + colleague_id + "/reset-password",
                  {"version": enabled["version"], "newPassword": temp})
    colleague.request("GET", path, status=401, code="AUTH_REQUIRED")
    bad = Session(base)
    bad.request("POST", "/auth/login", {"username": other_name, "password": colleague_pass},
                status=401, code="INVALID_CREDENTIALS")
    demand(colleague.login(other_name, temp)["mustChangePassword"], "重置后未强制改密")
    author.request("POST", "/auth/logout", status=204)
    author.request("GET", "/auth/me", status=401, code="AUTH_REQUIRED")
    print("PASS 停用、重新启用、密码重置及退出")
    print("HTTP 验收完成；工单号", order["orderNo"])


if __name__ == "__main__":
    run()
