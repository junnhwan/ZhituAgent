-- 用途：
-- 1. 为 zhitu_agent_knowledge 表补齐 hybrid retrieval 所需的轻量 lexical 检索加速能力
-- 2. 当前实现使用 PostgreSQL `ILIKE` + token 匹配，配合 `pg_trgm` 索引提升模糊匹配性能
--
-- 执行方式：
-- psql -U postgres -d postgres -f docs/sql/03-add-hybrid-retrieval-support.sql
--
-- 注意：
-- 1. 当前脚本默认知识表为 `public.zhitu_agent_knowledge`
-- 2. 如果你改过 schema / table 名，请先调整下面的索引目标
-- 3. 这个脚本不会删除已有数据，可以重复执行

\connect zhitu_agent

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_zhitu_agent_knowledge_text_trgm
    ON public.zhitu_agent_knowledge
    USING gin (text gin_trgm_ops);

ANALYZE public.zhitu_agent_knowledge;
