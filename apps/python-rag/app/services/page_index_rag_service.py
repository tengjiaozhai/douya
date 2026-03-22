"""PageIndexRAG 的核心服务层。

这份代码可以把它想成一个“图书管理员”：
1) `ingest`：把新书（文档）拆页、拆块、建索引，放进书架。
2) `query`：根据问题先粗筛，再精排，最后给出引用和答案。
3) `status`：告诉你书架里现在有多少书/页/块。

为了便于初学者理解，下面会在关键步骤加“小例子”。
"""

from __future__ import annotations

# 记录运行日志，便于排查线上问题。
import logging
# defaultdict 可以让“字典里的数字/列表”自动初始化。
from collections import defaultdict
# Iterable 用于类型标注（可迭代对象）。
from typing import Iterable
# 生成随机 UUID，给新文档当唯一 doc_id。
from uuid import uuid4

# 读取检索参数配置（阈值、top_k、窗口等）。
from app.core.config import RagConfig
# 生成答案相关类型（当前可选，不一定启用）。
from app.core.generator import AnswerGenerator, EvidenceSnippet
# 重排器接口与输入结构。
from app.core.reranker import PageReranker, RerankItem
# 检索数学工具：向量相似度、稀疏相似度、RRF 融合等。
from app.core.retrieval import (
    build_idf,
    cosine_similarity,
    hash_embedding,
    keyword_match_score,
    rrf_fusion_multi,
    sparse_similarity,
    sparse_terms,
)
# 文本处理工具：切词、分页、分块、规范化。
from app.indexing.chunker import chunk_tokens, normalize_text, split_pages, tokenize
# 数据模型定义（请求、响应、存储快照等）。
from app.models.schemas import (
    Citation,
    IngestRequest,
    IngestResponse,
    QueryDebug,
    QueryRequest,
    QueryResponse,
    StatusResponse,
    StorageSnapshot,
    StoredChunk,
    StoredDocument,
    StoredPage,
    utc_now_iso,
)
# 本地 JSON 仓库读写实现。
from app.storage.repository import JsonRepository

# 模块级日志器。
logger = logging.getLogger(__name__)


def _chunk_text_from_tokens(tokens: list[str]) -> str:
    """把 token 列表拼回字符串，作为 chunk_text 存储。

    为什么单独做成函数：
    - 保证所有 chunk 的拼接策略一致（可复现）。
    - 后续如果想改成别的拼接方式，只改这一个地方。
    """
    # 用空格拼接，保持紧凑且稳定。
    return " ".join(tokens)


class PageIndexRagService:
    """PageIndexRAG 业务服务。

    你可以把它看成三步流水线：
    - 入库：`ingest`
    - 查询：`query`
    - 状态：`status`
    """

    def __init__(
        self,
        repo: JsonRepository,
        rag_config: RagConfig,
        reranker: PageReranker | None = None,
        generator: AnswerGenerator | None = None,
    ) -> None:
        # 仓库：负责把快照读出来/写回去。
        self.repo = repo
        # 检索配置：各种阈值都在这里。
        self.cfg = rag_config
        # 可选重排器：没有时就走基础排序。
        self.reranker = reranker
        # 可选答案生成器：没有时只返回“未配置生成器”提示。
        self.generator = generator

    def ingest(self, req: IngestRequest) -> IngestResponse:
        """把文档写入索引。

        小例子：
        - 输入 2 页文本
        - 可能切成 2 个 page + 8 个 chunk
        - 最终会更新 documents/pages/chunks 三个区域
        """
        # 把请求里的 content/pages 统一提取为“页面列表”。
        pages = self._extract_pages(req)
        # 没有可用页面就拒绝入库。
        if not pages:
            raise ValueError("No valid content/pages provided for ingestion.")

        # 读取当前快照（内存里操作，最后一次性保存）。
        snapshot = self.repo.load()
        # 调用方没传 doc_id 就自动生成。
        doc_id = req.doc_id or str(uuid4())
        # 看看这个 doc_id 是否已存在（决定是新建还是覆盖更新）。
        doc = snapshot.documents.get(doc_id)
        # 统一时间戳（本次入库各对象共享同一时刻）。
        now = utc_now_iso()

        # 如果不存在，创建新文档元信息。
        if doc is None:
            doc = StoredDocument(
                doc_id=doc_id,
                doc_name=req.doc_name,
                version=req.version,
                metadata=req.metadata,
                created_at=now,
                updated_at=now,
            )
        else:
            # 已存在时走“覆盖更新”语义。
            # 这意味着同一个 doc_id 会被新内容替换。
            doc.doc_name = req.doc_name
            doc.version = req.version
            doc.metadata = req.metadata
            doc.updated_at = now
            # 先删旧页/旧块，避免历史数据残留。
            self._remove_doc_data(snapshot, doc_id)

        # 把最新文档对象放回 documents 区域。
        snapshot.documents[doc_id] = doc
        # 记录本次新建的 chunk，后续用于返回统计。
        all_new_chunks: list[StoredChunk] = []

        # 逐页处理（页码从 1 开始，符合人类习惯）。
        for idx, page_text in enumerate(pages, start=1):
            # page_id 规则：<doc_id>:p<page_no>
            page_id = f"{doc_id}:p{idx}"
            # 先对页面做切词，用于关键词和分块。
            page_tokens = tokenize(page_text)
            # 组装页面对象。
            page = StoredPage(
                page_id=page_id,
                doc_id=doc_id,
                page_no=idx,
                page_text=page_text,
                # 截断形成短摘要（用于展示）。
                page_summary=page_text[: self.cfg.summary_chars],
                # 提取高频关键词（简单频次法）。
                keywords=_top_keywords(page_tokens, n=8),
            )
            # 写入 pages 区域。
            snapshot.pages[page_id] = page

            # 对页面 token 做滑动窗口切块。
            # 例子：size=200, overlap=50
            # 块1: 0-200, 块2:150-350 ...
            ranges = chunk_tokens(page_tokens, self.cfg.child_chunk_size, self.cfg.child_overlap)
            # 逐块建索引。
            for chunk_no, (start, end, part_tokens) in enumerate(ranges, start=1):
                # chunk 文本由 token 拼回去。
                text = _chunk_text_from_tokens(part_tokens)
                # chunk_id 规则：<doc_id>:p<page_no>:c<chunk_no>
                chunk_id = f"{page_id}:c{chunk_no}"
                # 构建 chunk 对象。
                chunk = StoredChunk(
                    chunk_id=chunk_id,
                    page_id=page_id,
                    doc_id=doc_id,
                    page_no=idx,
                    chunk_no=chunk_no,
                    # token 在原页中的起止偏移（便于定位）。
                    offset_start=start,
                    offset_end=end,
                    chunk_text=text,
                    token_count=len(part_tokens),
                    # 稠密向量：用于向量相似度检索。
                    dense_vector=hash_embedding(text, self.cfg.vector_dim),
                    # 稀疏词项：用于词法检索。
                    sparse_terms=sparse_terms(text),
                )
                # 写入 chunks 区域。
                snapshot.chunks[chunk_id] = chunk
                # 记录统计。
                all_new_chunks.append(chunk)

        # 更新全局快照时间。
        snapshot.updated_at = now
        # 一次性落盘保存。
        self.repo.save(snapshot)
        # 返回入库结果。
        return IngestResponse(
            doc_id=doc_id,
            version=req.version,
            page_count=len(pages),
            chunk_count=len(all_new_chunks),
            updated_at=now,
        )

    def query(self, req: QueryRequest) -> QueryResponse:
        """执行检索查询。

        查询流程（可以理解为四关）：
        1) chunk 召回（dense + sparse + keyword + RRF 融合）
        2) page 聚合（同页 chunk 分数累加）
        3) 邻页扩展（补充上下文）
        4) 重排 + 证据抽取（给 citation）
        """
        # 加载最新快照。
        snapshot = self.repo.load()
        # 记录起始日志（只截断前 60 字，避免日志过长）。
        logger.info("query_start query=%s top_k=%s", req.query[:60], req.top_k)
        # 没有 chunk 时直接返回“空知识库”提示。
        if not snapshot.chunks:
            logger.info("query_empty_store")
            return QueryResponse(
                answer="知识库为空，请先调用 ingest 接口导入文档。",
                citations=[],
                debug=QueryDebug(
                    retrieved_chunks=0,
                    candidate_pages=0,
                    rerank_pages=0,
                    reranker=getattr(self.reranker, "name", None),
                    generator=getattr(self.generator, "name", None),
                    retrieval_source=None,
                    retrieval_route_hits={},
                )
                if req.with_debug
                else None,
            )

        # 规范化问题文本（去噪/空白规整）。
        query = normalize_text(req.query)
        # 为问题构建稠密向量。
        q_vector = hash_embedding(query, self.cfg.vector_dim)
        # 为问题构建稀疏词项。
        q_terms = sparse_terms(query)
        # 为问题构建关键词序列（用于关键字路由）。
        q_tokens = tokenize(query)
        # 获取融合后的 chunk 分数。
        fused, retrieval_source, route_hits = self._fused_chunk_scores(snapshot, q_vector, q_terms, q_tokens)

        # 把 chunk 按融合分从高到低排序。
        scored_chunks = sorted(fused.items(), key=lambda x: x[1], reverse=True)
        # 第一轮筛选：只保留超过阈值的 chunk。
        selected_chunks = [
            (snapshot.chunks[chunk_id], score)
            for chunk_id, score in scored_chunks
            if score >= self.cfg.min_score_threshold
        ]
        # 阈值太高时可能一个都选不到，这里做兜底。
        if not selected_chunks:
            # 兜底策略：直接取前 N 个（N 至少 10，或 top_k*2）。
            selected_chunks = [
                (snapshot.chunks[chunk_id], score)
                for chunk_id, score in scored_chunks[: max(10, req.top_k * 2)]
            ]
            logger.info("query_threshold_fallback selected=%s", len(selected_chunks))

        # page_scores: 每页总分（把同页 chunk 分数相加）。
        page_scores: dict[str, float] = defaultdict(float)
        # page_to_chunks: 保存“某页有哪些命中 chunk”。
        page_to_chunks: dict[str, list[tuple[StoredChunk, float]]] = defaultdict(list)
        for chunk, score in selected_chunks:
            page_scores[chunk.page_id] += score
            page_to_chunks[chunk.page_id].append((chunk, score))

        # 候选页池：先按页分排序，再截取 page_pool_size。
        candidate_pages = sorted(page_scores.items(), key=lambda x: x[1], reverse=True)[: self.cfg.page_pool_size]
        # 邻页扩展：例如命中第 10 页时，把第 9/11 页也加入候选（补上下文）。
        expanded_pages = self._expand_neighbors(candidate_pages, snapshot.pages)
        # 对候选页做重排（可用 lexical/bge）。
        reranked_pages = self._rerank_pages(query, expanded_pages, snapshot)
        # 取最终 top pages（同时受 top_k 和 max_context_pages 双重限制）。
        top_pages = reranked_pages[: min(req.top_k, self.cfg.max_context_pages)]

        # 准备返回引用和证据片段。
        citations: list[Citation] = []
        evidence_snippets: list[EvidenceSnippet] = []
        # 对每个 top page，取分数最高的前 2 个 chunk 作为证据。
        for page_id, _ in top_pages:
            chunks_scored = sorted(page_to_chunks.get(page_id, []), key=lambda x: x[1], reverse=True)
            for chunk, _ in chunks_scored[:2]:
                citations.append(Citation(doc_id=chunk.doc_id, page_no=chunk.page_no, chunk_id=chunk.chunk_id))
                evidence_snippets.append(EvidenceSnippet(page_no=chunk.page_no, text=chunk.chunk_text))

        # 组合答案（若未配置 generator，会返回提示语）。
        answer = self._compose_answer(query, evidence_snippets)
        # 记录结束日志，便于观察召回规模与排序效果。
        logger.info(
            "query_done retrieved=%s candidate_pages=%s rerank_pages=%s source=%s route_hits=%s reranker=%s generator=%s",
            len(selected_chunks),
            len(candidate_pages),
            len(top_pages),
            retrieval_source,
            route_hits,
            getattr(self.reranker, "name", None),
            getattr(self.generator, "name", None),
        )
        # 可选 debug 字段（由请求控制）。
        debug = None
        if req.with_debug:
            debug = QueryDebug(
                retrieved_chunks=len(selected_chunks),
                candidate_pages=len(candidate_pages),
                rerank_pages=len(top_pages),
                reranker=getattr(self.reranker, "name", None),
                generator=getattr(self.generator, "name", None),
                retrieval_source=retrieval_source,
                retrieval_route_hits=route_hits,
            )
        # 返回查询结果；citations 按 top_k 截断，避免过多。
        return QueryResponse(answer=answer, citations=citations[: req.top_k], debug=debug)

    def status(self) -> StatusResponse:
        """返回当前索引规模。"""
        # 加载快照。
        snapshot = self.repo.load()
        # 统计 docs/pages/chunks 数量。
        return StatusResponse(
            docs=len(snapshot.documents),
            pages=len(snapshot.pages),
            chunks=len(snapshot.chunks),
            updated_at=snapshot.updated_at,
        )

    def _remove_doc_data(self, snapshot: StorageSnapshot, doc_id: str) -> None:
        """删除某个文档的旧页和旧块（用于覆盖更新）。"""
        # 找出该文档下的所有 page_id。
        remove_pages = [pid for pid, p in snapshot.pages.items() if p.doc_id == doc_id]
        # 逐个删除页面记录。
        for pid in remove_pages:
            snapshot.pages.pop(pid, None)
        # 找出该文档下的所有 chunk_id。
        remove_chunks = [cid for cid, c in snapshot.chunks.items() if c.doc_id == doc_id]
        # 逐个删除块记录。
        for cid in remove_chunks:
            snapshot.chunks.pop(cid, None)

    def _extract_pages(self, req: IngestRequest) -> list[str]:
        """从入库请求提取页面文本。

        规则：
        - 优先使用 `req.pages`（调用方已经分好页）
        - 否则使用 `req.content` 自动切页
        """
        # 如果请求里已给 pages，逐页规范化并过滤空页。
        if req.pages:
            return [normalize_text(p) for p in req.pages if normalize_text(p)]
        # 如果只有 content，就交给 split_pages 自动切分。
        if req.content:
            return split_pages(req.content)
        # 都没有则返回空列表。
        return []

    def _expand_neighbors(
        self,
        candidate_pages: list[tuple[str, float]],
        pages: dict[str, StoredPage],
    ) -> list[tuple[str, float]]:
        """做邻页扩展（Small-to-Big 思想）。

        小例子：
        - 命中 page 10 分数 0.8
        - 邻页窗口=1，则补 page 9 和 page 11
        - 邻页分数设为 0.8 * 0.75 = 0.6
        """
        # 先放入原始候选页分数。
        merged: dict[str, float] = {pid: score for pid, score in candidate_pages}
        # 建立 (doc_id, page_no) -> page_id 映射，方便 O(1) 找邻页。
        page_index = {(p.doc_id, p.page_no): p.page_id for p in pages.values()}
        # 遍历每个候选页。
        for pid, score in candidate_pages:
            page = pages.get(pid)
            if page is None:
                continue
            # delta=1..window，表示前后第几页。
            for delta in range(1, self.cfg.neighbor_window + 1):
                # 同时看前一页和后一页。
                for next_no in (page.page_no - delta, page.page_no + delta):
                    nb_pid = page_index.get((page.doc_id, next_no))
                    # 只添加“存在且尚未加入”的邻页。
                    if nb_pid and nb_pid not in merged:
                        # 邻页分数打折，避免压过核心命中页。
                        merged[nb_pid] = score * 0.75
        # 返回按分数降序排列的扩展结果。
        return sorted(merged.items(), key=lambda x: x[1], reverse=True)

    def _rerank_pages(
        self,
        query: str,
        expanded_pages: list[tuple[str, float]],
        snapshot: StorageSnapshot,
    ) -> list[tuple[str, float]]:
        """对候选页做第二阶段排序。"""
        # 没有重排器就直接截断返回。
        if self.reranker is None:
            return expanded_pages[: self.cfg.rerank_top_k]
        # 组装重排输入。
        items: list[RerankItem] = []
        for page_id, base_score in expanded_pages:
            page = snapshot.pages.get(page_id)
            if page is None:
                continue
            items.append(RerankItem(item_id=page_id, text=page.page_text, base_score=base_score))
        # 调用重排器并截取前 rerank_top_k。
        return self.reranker.rerank(query, items)[: self.cfg.rerank_top_k]

    def _compose_answer(self, query: str, snippets: Iterable[EvidenceSnippet]) -> str:
        """把证据片段交给生成器，得到最终答案文本。"""
        # 过滤空文本片段，避免噪声。
        selected = [s for s in snippets if s.text]
        # 未配置生成器时，返回明确提示（当前很多 CLI 场景就是这样）。
        if self.generator is None:
            return f"未配置生成器。问题：{query}。"
        # 有生成器时走正常生成流程。
        return self.generator.generate(query, selected)

    def _fused_chunk_scores(
        self,
        snapshot: StorageSnapshot,
        q_vector: list[float],
        q_terms: dict[str, float],
        q_tokens: list[str],
    ) -> tuple[dict[str, float], str, dict[str, int]]:
        """融合多路 chunk 召回分数（dense + sparse + keyword -> RRF）。

        这里是“检索难点”之一，建议这样理解：
        - dense 像“语义相近”雷达：同义词也可能命中
        - sparse 像“关键词精确匹配”：字面词一致时很稳
        - keyword 像“术语硬匹配”：专有名词/型号词命中更直接
        - RRF 融合让多路互补，减少单一路径偏差
        """
        # 取所有 chunk 作为候选池。
        chunks = list(snapshot.chunks.values())
        # 收集所有 chunk 的稀疏词典，后面用于构造 IDF。
        all_sparse_docs = [c.sparse_terms for c in chunks]
        # 基于当前语料构建 IDF。
        idf = build_idf(all_sparse_docs)
        # dense 路：余弦相似度排序，取前 dense_top_k。
        dense_sorted = sorted(
            ((c.chunk_id, cosine_similarity(q_vector, c.dense_vector)) for c in chunks),
            key=lambda x: x[1],
            reverse=True,
        )[: self.cfg.dense_top_k]
        # sparse 路：词法相似度排序，取前 sparse_top_k。
        sparse_sorted = sorted(
            ((c.chunk_id, sparse_similarity(q_terms, c.sparse_terms, idf)) for c in chunks),
            key=lambda x: x[1],
            reverse=True,
        )
        # 稀疏分为 0 的候选对排序没有意义，提前过滤噪声。
        sparse_sorted = [item for item in sparse_sorted if item[1] > 0][: self.cfg.sparse_top_k]
        # keyword 路：关键词匹配排序，取前 keyword_top_k。
        keyword_sorted = sorted(
            ((c.chunk_id, keyword_match_score(q_tokens, c.sparse_terms, idf)) for c in chunks),
            key=lambda x: x[1],
            reverse=True,
        )
        # 关键词路同样过滤 0 分噪声，避免无命中时误加名次。
        keyword_sorted = [item for item in keyword_sorted if item[1] > 0][: self.cfg.keyword_top_k]
        # 把“排序列表”转成“名次字典”。
        # 例子：第 1 名记为 rank=1，第 2 名记为 rank=2。
        dense_ranks = {chunk_id: i + 1 for i, (chunk_id, _) in enumerate(dense_sorted)}
        sparse_ranks = {chunk_id: i + 1 for i, (chunk_id, _) in enumerate(sparse_sorted)}
        keyword_ranks = {chunk_id: i + 1 for i, (chunk_id, _) in enumerate(keyword_sorted)}

        # 记录每一路命中数，便于 debug 观察。
        route_hits = {
            "dense": len(dense_ranks),
            "sparse": len(sparse_ranks),
            "keyword": len(keyword_ranks),
        }
        # 汇总参与融合的路由标签。
        active_routes = [name for name, hits in route_hits.items() if hits > 0]
        retrieval_source = f"local_rrf[{'+'.join(active_routes)}]" if active_routes else "local_rrf[none]"
        # 返回融合分数与来源标识。
        return (
            rrf_fusion_multi([dense_ranks, sparse_ranks, keyword_ranks], self.cfg.rrf_k),
            retrieval_source,
            route_hits,
        )


def _top_keywords(tokens: list[str], n: int) -> list[str]:
    """从 token 列表里取前 n 个高频词。"""
    # 词频统计字典。
    freq: dict[str, int] = {}
    # 遍历所有 token，逐个计数。
    for t in tokens:
        freq[t] = freq.get(t, 0) + 1
    # 按频次降序，返回前 n 个词。
    return [k for k, _ in sorted(freq.items(), key=lambda x: x[1], reverse=True)[:n]]
