/**
 * Copyright 2025 The Google AI Edge Authors.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.localagents.rag.models;

import com.google.ai.edge.localagents.rag.models.proto.EmbedText;
import com.google.ai.edge.localagents.rag.models.proto.TextEmbeddingRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.File;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

// TODO(b/434785247): Add test for this class once we have the QAT model.
/** The on-device Gemma embedding model. */
public final class GemmaEmbeddingModel implements Embedder<String> {
  public static final String TITLE_KEY = "title";
  private final long modelHandle;
  private final Executor workerExecutor;

  static {
    System.loadLibrary("gemma_embedding_model_jni");
  }

  /**
   * Creates an on-device Gemma embedding model.
   *
   * @param embeddingModelPath The path of the embedding model.
   * @param sentencePieceModelPath Path to the sentence piece model.
   * @param useGpu If set to True, will use the GPU, otherwise will use the CPU for inference.
   */
  public GemmaEmbeddingModel(
      String embeddingModelPath, String sentencePieceModelPath, boolean useGpu) {
    validatePath(embeddingModelPath);
    validatePath(sentencePieceModelPath);
    modelHandle =
        nativeInitializeGemmaEmbeddingModel(embeddingModelPath, sentencePieceModelPath, useGpu);
    workerExecutor =
        Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder()
                .setNameFormat("gemma-embedder-pool-%d")
                .setPriority(Thread.NORM_PRIORITY)
                .build());
  }

  @Override
  public ListenableFuture<ImmutableList<Float>> getEmbeddings(EmbeddingRequest<String> request) {
    return Futures.submit(
        () -> ImmutableList.copyOf(nativeGetEmbeddingsProto(modelHandle, toProtoBytes(request))),
        workerExecutor);
  }

  @Override
  public ListenableFuture<ImmutableList<ImmutableList<Float>>> getBatchEmbeddings(
      EmbeddingRequest<String> request) {
    return Futures.submit(
        () -> {
          List<List<Float>> embeddings =
              nativeGetBatchEmbeddingsProto(modelHandle, toProtoBytes(request));
          ImmutableList.Builder<ImmutableList<Float>> embeddingsList = ImmutableList.builder();
          for (List<Float> embedding : embeddings) {
            embeddingsList.add(ImmutableList.copyOf(embedding));
          }
          return embeddingsList.build();
        },
        workerExecutor);
  }

  private static EmbedText.TaskType toProtoTaskType(EmbedData.TaskType taskType) {
    // Make sure to keep the enum values in sync between layers.
    // (-- LINT.IfChange --)
    return switch (taskType) {
      case RETRIEVAL_QUERY -> EmbedText.TaskType.RETRIEVAL_QUERY;
      case RETRIEVAL_DOCUMENT -> EmbedText.TaskType.RETRIEVAL_DOCUMENT;
      case SEMANTIC_SIMILARITY -> EmbedText.TaskType.SEMANTIC_SIMILARITY;
      case CLASSIFICATION -> EmbedText.TaskType.CLASSIFICATION;
      case CLUSTERING -> EmbedText.TaskType.CLUSTERING;
      case QUESTION_ANSWERING -> EmbedText.TaskType.QUESTION_ANSWERING;
      case FACT_VERIFICATION -> EmbedText.TaskType.FACT_VERIFICATION;
      default -> EmbedText.TaskType.TASK_TYPE_UNSPECIFIED;
    };
    // (--
    // LINT.ThenChange(
    // //depot/https://github.com/google-ai-edge/ai-edge-apis/tree/main/local_agents/rag/core/protos/embedding_models.proto,
    // //depot/https://github.com/google-ai-edge/ai-edge-apis/tree/main/local_agents/rag/java/com/google/ai/edge/localagents/rag/models/EmbedData.java
    // )
    // --)
  }

  private static void validatePath(String path) {
    File file = new File(path);
    if (!file.exists()) {
      throw new IllegalArgumentException(
          "File not found at " + path + ". Please check your configuration settings.");
    }
  }

  private static byte[] toProtoBytes(EmbeddingRequest<String> request) {
    var builder = TextEmbeddingRequest.newBuilder();
    for (var embedData : request.getEmbedData()) {
      var embedTextBuilder =
          EmbedText.newBuilder()
              .setText(embedData.getData())
              .setTask(toProtoTaskType(embedData.getTask()));
      if (embedData.getMetadata().containsKey(TITLE_KEY)) {
        embedTextBuilder.setTitle(embedData.getMetadata().get(TITLE_KEY).toString());
      }
      builder.addEmbedTexts(embedTextBuilder.build());
    }
    return builder.build().toByteArray();
  }

  private static native long nativeInitializeGemmaEmbeddingModel(
      String embeddingModelPath, String sentencePieceModelPath, boolean useGpu);

  private static native List<Float> nativeGetEmbeddingsProto(long modelHandle, byte[] request);

  private static native List<List<Float>> nativeGetBatchEmbeddingsProto(
      long modelHandle, byte[] request);
}
