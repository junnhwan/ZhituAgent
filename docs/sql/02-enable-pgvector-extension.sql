-- 用途：
-- 1. 连接到 zhitu_agent 数据库
-- 2. 启用 pgvector 扩展
--
-- 执行方式：
-- psql -U postgres -d postgres -f docs/sql/02-enable-pgvector-extension.sql
--
-- 注意：
-- 1. 这个脚本默认数据库名是 zhitu_agent
-- 2. 如果 CREATE EXTENSION vector 失败，说明当前容器镜像里未安装 pgvector 扩展

\connect zhitu_agent

CREATE EXTENSION IF NOT EXISTS vector;

GRANT CONNECT ON DATABASE zhitu_agent TO zhitu_agent;
GRANT USAGE, CREATE ON SCHEMA public TO zhitu_agent;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
    ON TABLES TO zhitu_agent;

