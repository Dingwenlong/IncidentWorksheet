CREATE TABLE IF NOT EXISTS sys_user (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 display_name VARCHAR(40) NOT NULL,
 role VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN','SUPERVISOR','TECHNICIAN')),
 status VARCHAR(8) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 password_hash VARCHAR(255) NOT NULL, must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
 auth_revision BIGINT NOT NULL DEFAULT 1, version INT NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX ix_user_role_status_id (role,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS equipment (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(40) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 name VARCHAR(100) NOT NULL, location VARCHAR(200), status VARCHAR(8) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 version INT NOT NULL DEFAULT 1, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), INDEX ix_equipment_status_id (status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS work_order (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_no CHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 equipment_id BIGINT NOT NULL, equipment_code VARCHAR(40) NOT NULL, equipment_name VARCHAR(100) NOT NULL,
 title VARCHAR(100) NOT NULL, fault_text TEXT NOT NULL, action_text TEXT NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('PENDING','PROCESSING','COMPLETED')), creator_id BIGINT NOT NULL,
 version INT NOT NULL DEFAULT 1,text_version INT NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 creation_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,creation_hash BINARY(32) NOT NULL,
 UNIQUE KEY uk_order_creation(creator_id,creation_key),INDEX ix_order_equipment(equipment_id,created_at,id),
 INDEX ix_order_status(status,created_at,id),INDEX ix_order_creator(creator_id,created_at,id),INDEX ix_order_time(created_at,id),
 FOREIGN KEY(equipment_id) REFERENCES equipment(id), FOREIGN KEY(creator_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS work_order_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,order_id BIGINT NOT NULL,type VARCHAR(16) NOT NULL,
 from_status VARCHAR(16),to_status VARCHAR(16) NOT NULL,actor_id BIGINT NOT NULL,actor_name VARCHAR(40) NOT NULL,
 occurred_at DATETIME(3) NOT NULL, INDEX ix_event_time(order_id,occurred_at,id),
 FOREIGN KEY(order_id) REFERENCES work_order(id), FOREIGN KEY(actor_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS work_order_summary (
 order_id BIGINT NOT NULL,algorithm VARCHAR(8) NOT NULL CHECK(algorithm IN ('TFIDF','TEXTRANK')),
 text_version INT NOT NULL CHECK(text_version>=1), algorithm_version VARCHAR(64) NOT NULL,
 dictionary_version VARCHAR(64) NOT NULL, idf_version VARCHAR(64),
 fault JSON NOT NULL, action JSON NOT NULL, PRIMARY KEY(order_id,algorithm), FOREIGN KEY(order_id) REFERENCES work_order(id),
 CHECK((algorithm='TFIDF' AND idf_version IS NOT NULL) OR (algorithm='TEXTRANK' AND idf_version IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
