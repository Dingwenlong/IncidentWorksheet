package cn.workorders.auth;

import cn.workorders.common.ApiException;

enum DemoRole {
  ADMIN("demo_admin", "管理员测试"),
  SUPERVISOR("demo_supervisor", "主管测试"),
  TECHNICIAN("demo_technician", "维修员测试");

  final String username;
  final String displayName;

  DemoRole(String username, String displayName) {
    this.username = username;
    this.displayName = displayName;
  }

  static DemoRole from(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid("role", "请选择有效的测试身份");
    }
  }
}
