-- 用途：
-- 1. 创建 zhitu-agent 的 PostgreSQL 账号
-- 2. 创建业务数据库
--
-- 执行建议：
-- 1. 先连接 postgres 默认库
-- 2. 先执行本文件前半段的 CREATE ROLE
-- 3. 再单独执行 CREATE DATABASE 语句
--
-- 注意：
-- 1. 请先把下面的密码占位符改掉
-- 2. 如果你已经有可复用的业务账号，可以跳过 CREATE ROLE 那段
-- 3. CREATE DATABASE 不能放进 DO 块里，也不建议依赖 \gexec 这类 psql 专有命令
-- 4. 如果数据库已存在，CREATE DATABASE 会报错，此时跳过即可

DO
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = 'zhitu_agent'
    ) THEN
        CREATE ROLE zhitu_agent
            LOGIN
            PASSWORD 'CHANGE_ME_STRONG_PASSWORD';
    END IF;
END
$$;

-- 如果你已确认数据库不存在，再执行这一句：
-- CREATE DATABASE zhitu_agent OWNER zhitu_agent;

-- 数据库创建成功后，再执行这一句：
-- ALTER DATABASE zhitu_agent OWNER TO zhitu_agent;
