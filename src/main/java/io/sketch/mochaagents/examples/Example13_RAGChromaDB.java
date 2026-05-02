package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.core.impl.CodeAgent;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.*;

/**
 * Example13 — 对应 smolagents 的 rag_using_chromadb.py.
 *
 * <p>演示基于向量存储 (ChromaDB-style) 的 RAG 模式:
 * <ul>
 *   <li>文档加载与分块 (模拟 PDF/数据集加载)</li>
 *   <li>向量嵌入与相似度搜索 (模拟 ChromaDB vector store)</li>
 *   <li>RetrieverTool 使用语义搜索检索相关文档</li>
 *   <li>CodeAgent 基于检索结果回答问题</li>
 * </ul>
 *
 * <pre>
 *   smolagents 对应:
 *     knowledge_base = datasets.load_dataset("m-ric/huggingface_doc", split="train")
 *     text_splitter = RecursiveCharacterTextSplitter.from_huggingface_tokenizer(...)
 *     embeddings = HuggingFaceEmbeddings(...)
 *     vector_store = Chroma.from_documents(docs_processed, embeddings, ...)
 *     retriever_tool = RetrieverTool(vector_store)
 *     agent = CodeAgent(tools=[retriever_tool], model=model)
 * </pre>
 */
public final class Example13_RAGChromaDB {

    // ─── 模拟文档数据库 ───

    private static final String[] RAW_DOCS = {
            // HuggingFace 文档片段
            "To push a model to the Hugging Face Hub, you can use the push_to_hub() method. "
                    + "First, make sure you are authenticated with huggingface-cli login. "
                    + "Then call model.push_to_hub('your-model-name').",

            "The transformers library provides thousands of pretrained models to perform tasks "
                    + "on different modalities such as text, vision, and audio. You can load any "
                    + "model using AutoModel.from_pretrained('model-name').",

            "Tokenizers are responsible for converting raw text into the input format expected "
                    + "by the model. The AutoTokenizer class can automatically select the appropriate "
                    + "tokenizer for a given pretrained model.",

            "Pipelines are the easiest way to use a model for inference. The pipeline() function "
                    + "wraps all the preprocessing, forward pass, and postprocessing steps. "
                    + "Example: classifier = pipeline('sentiment-analysis').",

            "Training arguments in the Trainer API include: learning_rate, per_device_train_batch_size, "
                    + "num_train_epochs, and weight_decay. These are configured via TrainingArguments class.",

            "Datasets can be loaded from the Hub using load_dataset(). The library supports many "
                    + "formats including CSV, JSON, Parquet, and more. Use datasets.load_dataset('dataset-name').",

            "For fine-tuning, you typically need to prepare your dataset, load a pretrained model, "
                    + "configure training arguments, and then call trainer.train().",

            "Gradient checkpointing reduces memory usage during training by trading compute for memory. "
                    + "Enable it with model.gradient_checkpointing_enable().",

            "Mixed precision training uses fp16 or bf16 to speed up training. Set fp16=True in "
                    + "TrainingArguments to enable automatic mixed precision (AMP).",

            "The Model Hub hosts over 200,000 models from the community. You can browse, download, "
                    + "and contribute models through the hub at huggingface.co/models."
    };

    /**
     * 模拟向量存储 — 使用简单 TF-IDF 词袋模型进行相似度搜索.
     * 对应 smolagents 中的 Chroma vector store.
     */
    static final class VectorStore {
        private final List<String> documents;
        private final Map<String, Map<String, Double>> tfIdfVectors;

        VectorStore(List<String> documents) {
            this.documents = new ArrayList<>(documents);
            this.tfIdfVectors = buildTfIdf(documents);
        }

        /**
         * 语义相似度搜索 — 返回最相关的 k 个文档.
         */
        List<String> similaritySearch(String query, int k) {
            Map<String, Double> queryVec = tokenize(query);
            double queryNorm = norm(queryVec);

            // 计算余弦相似度
            PriorityQueue<Map.Entry<Integer, Double>> heap = new PriorityQueue<>(
                    Comparator.comparingDouble(Map.Entry::getValue));

            for (int i = 0; i < documents.size(); i++) {
                Map<String, Double> docVec = tfIdfVectors.getOrDefault(String.valueOf(i),
                        Collections.emptyMap());
                double sim = cosineSimilarity(queryVec, queryNorm, docVec);
                if (sim > 0) {
                    heap.offer(new AbstractMap.SimpleEntry<>(i, sim));
                    if (heap.size() > k) heap.poll();
                }
            }

            // 按相似度降序排列
            List<Map.Entry<Integer, Double>> sorted = new ArrayList<>(heap);
            sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            List<String> results = new ArrayList<>();
            for (var entry : sorted) {
                results.add(documents.get(entry.getKey()));
            }
            return results;
        }

        // ─── TF-IDF 计算 ───

        private static Map<String, Map<String, Double>> buildTfIdf(List<String> docs) {
            Map<String, Map<String, Double>> vectors = new HashMap<>();
            int n = docs.size();

            // 计算文档频率
            Map<String, Integer> docFreq = new HashMap<>();
            for (String doc : docs) {
                Set<String> unique = new HashSet<>(tokenize(doc).keySet());
                for (String term : unique) {
                    docFreq.merge(term, 1, Integer::sum);
                }
            }

            // 计算 TF-IDF 向量
            for (int i = 0; i < docs.size(); i++) {
                Map<String, Double> tf = tokenize(docs.get(i));
                Map<String, Double> tfidf = new HashMap<>();
                for (var entry : tf.entrySet()) {
                    double idf = Math.log((n + 1.0) / (docFreq.getOrDefault(entry.getKey(), 0) + 1.0));
                    tfidf.put(entry.getKey(), entry.getValue() * idf);
                }
                vectors.put(String.valueOf(i), tfidf);
            }
            return vectors;
        }

        private static Map<String, Double> tokenize(String text) {
            Map<String, Double> tokens = new HashMap<>();
            String[] words = text.toLowerCase()
                    .replaceAll("[^a-z0-9\\s]", " ")
                    .split("\\s+");
            for (String w : words) {
                if (w.length() > 1) {
                    tokens.merge(w, 1.0, Double::sum);
                }
            }
            return tokens;
        }

        private static double cosineSimilarity(Map<String, Double> a, double aNorm,
                                                Map<String, Double> b) {
            if (a.isEmpty() || b.isEmpty()) return 0;
            double dot = 0, bNorm = 0;
            for (var entry : a.entrySet()) {
                Double bVal = b.get(entry.getKey());
                if (bVal != null) dot += entry.getValue() * bVal;
            }
            for (double v : b.values()) bNorm += v * v;
            bNorm = Math.sqrt(bNorm);
            if (aNorm == 0 || bNorm == 0) return 0;
            return dot / (aNorm * bNorm);
        }

        private static double norm(Map<String, Double> vec) {
            double sum = 0;
            for (double v : vec.values()) sum += v * v;
            return Math.sqrt(sum);
        }
    }

    /**
     * ChromaDB 风格的 RetrieverTool — 使用向量存储进行语义搜索.
     */
    static final class RetrieverTool implements Tool {
        private final VectorStore vectorStore;

        RetrieverTool(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
        }

        @Override
        public String getName() { return "retriever"; }

        @Override
        public String getDescription() {
            return "Uses semantic search to retrieve the parts of documentation that could be "
                    + "most relevant to answer your query.";
        }

        @Override
        public Map<String, ToolInput> getInputs() {
            return Map.of("query", ToolInput.string(
                    "The query to perform. Use affirmative form rather than a question."));
        }

        @Override
        public String getOutputType() { return "string"; }

        @Override
        public Object call(Map<String, Object> args) {
            String query = (String) args.getOrDefault("query", "");
            List<String> docs = vectorStore.similaritySearch(query, 3);
            StringBuilder sb = new StringBuilder("\nRetrieved documents:\n");
            for (int i = 0; i < docs.size(); i++) {
                sb.append("\n===== Document ").append(i).append(" =====\n")
                        .append(docs.get(i));
            }
            return sb.toString();
        }

        @Override
        public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    }

    // ─── main ───

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example13: RAG with ChromaDB — 向量存储语义搜索 RAG");
        System.out.println("=".repeat(60));

        // Step 1: 文档分块（模拟 text_splitter）
        System.out.println("\n[1] Splitting documents... (" + RAW_DOCS.length + " source docs)");
        List<String> chunks = new ArrayList<>();
        for (String doc : RAW_DOCS) {
            chunks.add(doc);
        }
        System.out.println("    Total chunks after processing: " + chunks.size());

        // Step 2: 构建向量存储（模拟 ChromaDB embeddings）
        System.out.println("[2] Building vector store (TF-IDF embeddings)...");
        VectorStore vectorStore = new VectorStore(chunks);
        System.out.println("    Vector store ready with " + chunks.size() + " documents.");

        // 测试语义搜索
        String testQuery = "push model to hub";
        System.out.println("\n[3] Testing semantic search for: \"" + testQuery + "\"");
        List<String> results = vectorStore.similaritySearch(testQuery, 3);
        for (int i = 0; i < results.size(); i++) {
            System.out.println("    Result " + (i + 1) + " (score " +
                    String.format("%.3f", getSimilarity(testQuery, results.get(i), vectorStore)) +
                    "): " + results.get(i).substring(0, Math.min(80, results.get(i).length())) + "...");
        }

        // Step 3: 创建 RetrieverTool + CodeAgent
        System.out.println("\n[4] Creating CodeAgent with RetrieverTool...");
        var retrieverTool = new RetrieverTool(vectorStore);
        var registry = new ToolRegistry();
        registry.register(retrieverTool);

        var agent = CodeAgent.builder()
                .name("rag-chromadb-agent")
                .description("RAG agent using vector store retrieval")
                .llm(LLMFactory.create())
                .toolRegistry(registry)
                .maxSteps(4)
                .build();

        // Step 4: 执行查询
        System.out.println("\n[5] Running queries...\n");

        String[] queries = {
                "How can I push a model to the Hub?",
                "What is the pipeline function used for?",
                "How does gradient checkpointing help during training?"
        };

        for (String query : queries) {
            System.out.println("Query: " + query);
            try {
                String answer = agent.run(query);
                System.out.println("Answer: " + answer);
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
            System.out.println();
        }

        System.out.println("=".repeat(60));
        System.out.println("Example13 Complete.");
    }

    // 辅助方法：获取相似度分数
    private static double getSimilarity(String query, String doc, VectorStore vs) {
        var docs = vs.similaritySearch(query, vs.documents.size());
        for (int i = 0; i < docs.size(); i++) {
            if (docs.get(i).equals(doc)) {
                return (docs.size() - i) / (double) docs.size();
            }
        }
        return 0;
    }

    private Example13_RAGChromaDB() {}
}
