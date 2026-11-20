"""在 workorders_qa 专用库验证摘要写入回滚和损坏读取。

配置 WORKORDER_TEST_URL、WORKORDER_TEST_OWNER、WORKORDER_TEST_OWNER_PASSWORD、
WORKORDER_TEST_EQUIPMENT_ID，以及 WORKORDER_TEST_MYSQL（客户端可执行文件）、
WORKORDER_TEST_DB_PORT、WORKORDER_TEST_DB_USER、WORKORDER_TEST_DB_PASSWORD。
脚本仅在新建的验收工单上注入失败，运行后恢复摘要并移除测试约束。
"""

import os
import subprocess
from uuid import uuid4

from http_acceptance import Session, demand, verify_summary


def sql(statement):
    cmd = [os.environ["WORKORDER_TEST_MYSQL"], "--no-defaults", "--protocol=TCP",
           "--host=127.0.0.1", "--port=" + os.environ["WORKORDER_TEST_DB_PORT"],
           "--user=" + os.environ["WORKORDER_TEST_DB_USER"], "--database=workorders_qa",
           "--default-character-set=utf8mb4", "--batch", "--skip-column-names"]
    env = os.environ.copy()
    env["MYSQL_PWD"] = env.pop("WORKORDER_TEST_DB_PASSWORD")
    result = subprocess.run(cmd, input=statement.encode("utf-8"), capture_output=True,
                            timeout=20, env=env, check=True)
    return result.stdout.decode("utf-8").strip()


def run():
    owner = Session(os.environ["WORKORDER_TEST_URL"])
    owner.login(os.environ["WORKORDER_TEST_OWNER"], os.environ["WORKORDER_TEST_OWNER_PASSWORD"])
    token = uuid4().hex
    created = owner.request("POST", "/orders", {"equipmentId": os.environ["WORKORDER_TEST_EQUIPMENT_ID"],
        "title": "数据库事务验收" + token, "faultText": "E17。"}, key=uuid4(), status=201)
    order_id = created["id"]
    demand(order_id.isascii() and order_id.isdecimal(), "没有取得工单 ID")
    path = "/orders/" + order_id
    owner.request("POST", path + "/transitions", {"version": 1, "action": "START"})
    before = owner.request("GET", path)
    verify_summary(before)
    fixture = sql(f"SELECT count(*) FROM work_order WHERE id={order_id} AND title='数据库事务验收{token}';")
    demand(fixture == "1", "选定的数据库与测试站点不匹配")

    constraint = "qa_fail_" + token
    sql(f"""ALTER TABLE work_order_summary ADD CONSTRAINT {constraint}
            CHECK (order_id <> {order_id} OR algorithm <> 'TEXTRANK' OR text_version = 1);""")
    complete = {"version": 2, "action": "COMPLETE", "actionText": "更换触点，电机恢复转动。"}
    try:
        owner.request("POST", path + "/transitions", complete, status=(500, 503))
        demand(owner.request("GET", path) == before, "第二条摘要写入失败后，工单或摘要发生部分提交")
    finally:
        sql(f"ALTER TABLE work_order_summary DROP CHECK {constraint};")
    done = owner.request("POST", path + "/transitions", complete)
    detail = owner.request("GET", path)
    demand(done["version"] == 3 and len(detail["events"]) == 3, "失败后重试的状态或事件数错误")
    verify_summary(detail)
    print("PASS 第二份摘要写入失败后整单回滚，重试只提交一次")

    predicate = f"order_id={order_id} AND algorithm='TFIDF'"
    previous = sql(f"SELECT text_version FROM work_order_summary WHERE {predicate};")
    demand(previous == str(done["textVersion"]), "摘要版本预检不符")
    try:
        sql(f"UPDATE work_order_summary SET text_version=text_version+1 WHERE {predicate};")
        owner.request("GET", path, status=500, code="SUMMARY_DATA_INCONSISTENT")
        owner.request("GET", "/orders?keyword=" + token, status=500, code="SUMMARY_DATA_INCONSISTENT")
    finally:
        sql(f"UPDATE work_order_summary SET text_version={int(previous)} WHERE {predicate};")
    demand(owner.request("GET", path) == detail, "恢复后摘要结果没有还原")
    print("PASS 摘要版本不符时列表及详情拒绝呈现，修复后读取原值")


if __name__ == "__main__":
    run()
