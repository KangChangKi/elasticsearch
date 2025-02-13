/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.elasticsearch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.logging.DeprecationCategory;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.inference.ChunkedInferenceServiceResults;
import org.elasticsearch.inference.ChunkingOptions;
import org.elasticsearch.inference.InferenceResults;
import org.elasticsearch.inference.InferenceServiceExtension;
import org.elasticsearch.inference.InferenceServiceResults;
import org.elasticsearch.inference.InputType;
import org.elasticsearch.inference.Model;
import org.elasticsearch.inference.ModelConfigurations;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xpack.core.inference.results.ErrorChunkedInferenceResults;
import org.elasticsearch.xpack.core.inference.results.InferenceChunkedSparseEmbeddingResults;
import org.elasticsearch.xpack.core.inference.results.InferenceChunkedTextEmbeddingFloatResults;
import org.elasticsearch.xpack.core.inference.results.InferenceTextEmbeddingFloatResults;
import org.elasticsearch.xpack.core.inference.results.RankedDocsResults;
import org.elasticsearch.xpack.core.inference.results.SparseEmbeddingResults;
import org.elasticsearch.xpack.core.ml.action.GetTrainedModelsAction;
import org.elasticsearch.xpack.core.ml.action.InferModelAction;
import org.elasticsearch.xpack.core.ml.inference.results.ErrorInferenceResults;
import org.elasticsearch.xpack.core.ml.inference.results.MlChunkedTextEmbeddingFloatResults;
import org.elasticsearch.xpack.core.ml.inference.results.MlChunkedTextExpansionResults;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextEmbeddingConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextExpansionConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextSimilarityConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TokenizationConfigUpdate;
import org.elasticsearch.xpack.inference.services.ConfigurationParseContext;
import org.elasticsearch.xpack.inference.services.ServiceUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.elasticsearch.xpack.core.inference.results.ResultUtils.createInvalidChunkedResultException;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.removeFromMap;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.removeFromMapOrThrowIfNull;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.throwIfNotEmptyMap;
import static org.elasticsearch.xpack.inference.services.elasticsearch.ElserModels.ELSER_V2_MODEL;
import static org.elasticsearch.xpack.inference.services.elasticsearch.ElserModels.ELSER_V2_MODEL_LINUX_X86;

public class ElasticsearchInternalService extends BaseElasticsearchInternalService {

    public static final String NAME = "elasticsearch";
    public static final String OLD_ELSER_SERVICE_NAME = "elser";

    static final String MULTILINGUAL_E5_SMALL_MODEL_ID = ".multilingual-e5-small";
    static final String MULTILINGUAL_E5_SMALL_MODEL_ID_LINUX_X86 = ".multilingual-e5-small_linux-x86_64";
    public static final Set<String> MULTILINGUAL_E5_SMALL_VALID_IDS = Set.of(
        MULTILINGUAL_E5_SMALL_MODEL_ID,
        MULTILINGUAL_E5_SMALL_MODEL_ID_LINUX_X86
    );

    private static final Logger logger = LogManager.getLogger(ElasticsearchInternalService.class);
    private static final DeprecationLogger DEPRECATION_LOGGER = DeprecationLogger.getLogger(ElasticsearchInternalService.class);

    public ElasticsearchInternalService(InferenceServiceExtension.InferenceServiceFactoryContext context) {
        super(context);
    }

    // for testing
    ElasticsearchInternalService(
        InferenceServiceExtension.InferenceServiceFactoryContext context,
        Consumer<ActionListener<Set<String>>> platformArch
    ) {
        super(context, platformArch);
    }

    @Override
    protected EnumSet<TaskType> supportedTaskTypes() {
        return EnumSet.of(TaskType.RERANK, TaskType.TEXT_EMBEDDING, TaskType.SPARSE_EMBEDDING);
    }

    @Override
    public void parseRequestConfig(
        String inferenceEntityId,
        TaskType taskType,
        Map<String, Object> config,
        ActionListener<Model> modelListener
    ) {
        try {
            Map<String, Object> serviceSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.SERVICE_SETTINGS);
            Map<String, Object> taskSettingsMap = removeFromMap(config, ModelConfigurations.TASK_SETTINGS);
            String serviceName = (String) config.remove(ModelConfigurations.SERVICE); // required for elser service in elasticsearch service

            throwIfNotEmptyMap(config, name());

            String modelId = (String) serviceSettingsMap.get(ElasticsearchInternalServiceSettings.MODEL_ID);
            if (modelId == null) {
                if (OLD_ELSER_SERVICE_NAME.equals(serviceName)) {
                    // TODO complete deprecation of null model ID
                    // throw new ValidationException().addValidationError("Error parsing request config, model id is missing");
                    DEPRECATION_LOGGER.critical(
                        DeprecationCategory.API,
                        "inference_api_null_model_id_in_elasticsearch_service",
                        "Putting elasticsearch service inference endpoints (including elser service) without a model_id field is"
                            + " deprecated and will be removed in a future release. Please specify a model_id field."
                    );
                    platformArch.accept(
                        modelListener.delegateFailureAndWrap(
                            (delegate, arch) -> elserCase(inferenceEntityId, taskType, config, arch, serviceSettingsMap, modelListener)
                        )
                    );
                } else {
                    throw new IllegalArgumentException("Error parsing service settings, model_id must be provided");
                }
            } else if (MULTILINGUAL_E5_SMALL_VALID_IDS.contains(modelId)) {
                platformArch.accept(
                    modelListener.delegateFailureAndWrap(
                        (delegate, arch) -> e5Case(inferenceEntityId, taskType, config, arch, serviceSettingsMap, modelListener)
                    )
                );
            } else if (ElserModels.isValidModel(modelId)) {
                platformArch.accept(
                    modelListener.delegateFailureAndWrap(
                        (delegate, arch) -> elserCase(inferenceEntityId, taskType, config, arch, serviceSettingsMap, modelListener)
                    )
                );
            } else {
                customElandCase(inferenceEntityId, taskType, serviceSettingsMap, taskSettingsMap, modelListener);
            }
        } catch (Exception e) {
            modelListener.onFailure(e);
        }
    }

    private void customElandCase(
        String inferenceEntityId,
        TaskType taskType,
        Map<String, Object> serviceSettingsMap,
        Map<String, Object> taskSettingsMap,
        ActionListener<Model> modelListener
    ) {
        String modelId = (String) serviceSettingsMap.get(ElasticsearchInternalServiceSettings.MODEL_ID);
        var request = new GetTrainedModelsAction.Request(modelId);

        var getModelsListener = modelListener.<GetTrainedModelsAction.Response>delegateFailureAndWrap((delegate, response) -> {
            if (response.getResources().count() < 1) {
                throw new IllegalArgumentException(
                    "Error parsing request config, model id does not match any models available on this platform. Was ["
                        + modelId
                        + "]. You may need to load it into the cluster using eland."
                );
            } else {
                var model = createCustomElandModel(
                    inferenceEntityId,
                    taskType,
                    serviceSettingsMap,
                    taskSettingsMap,
                    ConfigurationParseContext.REQUEST
                );

                throwIfNotEmptyMap(serviceSettingsMap, name());
                throwIfNotEmptyMap(taskSettingsMap, name());

                delegate.onResponse(model);
            }
        });

        client.execute(GetTrainedModelsAction.INSTANCE, request, getModelsListener);
    }

    private static CustomElandModel createCustomElandModel(
        String inferenceEntityId,
        TaskType taskType,
        Map<String, Object> serviceSettings,
        Map<String, Object> taskSettings,
        ConfigurationParseContext context
    ) {

        return switch (taskType) {
            case TEXT_EMBEDDING -> new CustomElandEmbeddingModel(
                inferenceEntityId,
                taskType,
                NAME,
                CustomElandInternalTextEmbeddingServiceSettings.fromMap(serviceSettings, context)
            );
            case SPARSE_EMBEDDING -> new CustomElandModel(
                inferenceEntityId,
                taskType,
                NAME,
                elandServiceSettings(serviceSettings, context)
            );
            case RERANK -> new CustomElandRerankModel(
                inferenceEntityId,
                taskType,
                NAME,
                elandServiceSettings(serviceSettings, context),
                CustomElandRerankTaskSettings.fromMap(taskSettings)
            );
            default -> throw new ElasticsearchStatusException(TaskType.unsupportedTaskTypeErrorMsg(taskType, NAME), RestStatus.BAD_REQUEST);
        };
    }

    private static CustomElandInternalServiceSettings elandServiceSettings(
        Map<String, Object> settingsMap,
        ConfigurationParseContext context
    ) {
        return switch (context) {
            case REQUEST -> new CustomElandInternalServiceSettings(
                ElasticsearchInternalServiceSettings.fromRequestMap(settingsMap).build()
            );
            case PERSISTENT -> new CustomElandInternalServiceSettings(ElasticsearchInternalServiceSettings.fromPersistedMap(settingsMap));
        };
    }

    private void e5Case(
        String inferenceEntityId,
        TaskType taskType,
        Map<String, Object> config,
        Set<String> platformArchitectures,
        Map<String, Object> serviceSettingsMap,
        ActionListener<Model> modelListener
    ) {
        var esServiceSettingsBuilder = ElasticsearchInternalServiceSettings.fromRequestMap(serviceSettingsMap);

        if (esServiceSettingsBuilder.getModelId() == null) {
            esServiceSettingsBuilder.setModelId(
                selectDefaultModelVariantBasedOnClusterArchitecture(
                    platformArchitectures,
                    MULTILINGUAL_E5_SMALL_MODEL_ID_LINUX_X86,
                    MULTILINGUAL_E5_SMALL_MODEL_ID
                )
            );
        } else if (modelVariantValidForArchitecture(platformArchitectures, esServiceSettingsBuilder.getModelId()) == false) {
            throw new IllegalArgumentException(
                "Error parsing request config, model id does not match any models available on this platform. Was ["
                    + esServiceSettingsBuilder.getModelId()
                    + "]"
            );
        }

        throwIfNotEmptyMap(config, name());
        throwIfNotEmptyMap(serviceSettingsMap, name());

        modelListener.onResponse(
            new MultilingualE5SmallModel(
                inferenceEntityId,
                taskType,
                NAME,
                new MultilingualE5SmallInternalServiceSettings(esServiceSettingsBuilder.build())
            )
        );
    }

    static boolean modelVariantValidForArchitecture(Set<String> platformArchitectures, String modelId) {
        if (modelId.equals(MULTILINGUAL_E5_SMALL_MODEL_ID)) {
            // platform agnostic model is always compatible
            return true;
        }
        return modelId.equals(
            selectDefaultModelVariantBasedOnClusterArchitecture(
                platformArchitectures,
                MULTILINGUAL_E5_SMALL_MODEL_ID_LINUX_X86,
                MULTILINGUAL_E5_SMALL_MODEL_ID
            )
        );
    }

    private void elserCase(
        String inferenceEntityId,
        TaskType taskType,
        Map<String, Object> config,
        Set<String> platformArchitectures,
        Map<String, Object> serviceSettingsMap,
        ActionListener<Model> modelListener
    ) {
        var esServiceSettingsBuilder = ElasticsearchInternalServiceSettings.fromRequestMap(serviceSettingsMap);
        final String defaultModelId = selectDefaultModelVariantBasedOnClusterArchitecture(
            platformArchitectures,
            ELSER_V2_MODEL_LINUX_X86,
            ELSER_V2_MODEL
        );
        if (false == defaultModelId.equals(esServiceSettingsBuilder.getModelId())) {

            if (esServiceSettingsBuilder.getModelId() == null) {
                // TODO remove this case once we remove the option to not pass model ID
                esServiceSettingsBuilder.setModelId(defaultModelId);
            } else if (esServiceSettingsBuilder.getModelId().equals(ELSER_V2_MODEL)) {
                logger.warn(
                    "The platform agnostic model [{}] was requested on Linux x86_64. "
                        + "It is recommended to use the optimized model instead [{}]",
                    ELSER_V2_MODEL,
                    ELSER_V2_MODEL_LINUX_X86
                );
            } else {
                throw new IllegalArgumentException(
                    "Error parsing request config, model id does not match any models available on this platform. Was ["
                        + esServiceSettingsBuilder.getModelId()
                        + "]. You may need to use a platform agnostic model."
                );
            }
        }

        DEPRECATION_LOGGER.warn(
            DeprecationCategory.API,
            "inference_api_elser_service",
            "The [{}] service is deprecated and will be removed in a future release. Use the [{}] service instead, with"
                + " [model_id] set to [{}] in the [service_settings]",
            OLD_ELSER_SERVICE_NAME,
            ElasticsearchInternalService.NAME,
            defaultModelId
        );

        if (modelVariantDoesNotMatchArchitecturesAndIsNotPlatformAgnostic(platformArchitectures, esServiceSettingsBuilder.getModelId())) {
            throw new IllegalArgumentException(
                "Error parsing request config, model id does not match any models available on this platform. Was ["
                    + esServiceSettingsBuilder.getModelId()
                    + "]"
            );
        }

        throwIfNotEmptyMap(config, name());
        throwIfNotEmptyMap(serviceSettingsMap, name());

        modelListener.onResponse(
            new ElserInternalModel(
                inferenceEntityId,
                taskType,
                NAME,
                new ElserInternalServiceSettings(esServiceSettingsBuilder.build()),
                ElserMlNodeTaskSettings.DEFAULT
            )
        );
    }

    private static boolean modelVariantDoesNotMatchArchitecturesAndIsNotPlatformAgnostic(
        Set<String> platformArchitectures,
        String modelId
    ) {
        return modelId.equals(
            selectDefaultModelVariantBasedOnClusterArchitecture(
                platformArchitectures,
                MULTILINGUAL_E5_SMALL_MODEL_ID_LINUX_X86,
                MULTILINGUAL_E5_SMALL_MODEL_ID
            )
        );
    }

    @Override
    public Model parsePersistedConfigWithSecrets(
        String inferenceEntityId,
        TaskType taskType,
        Map<String, Object> config,
        Map<String, Object> secrets
    ) {
        return parsePersistedConfig(inferenceEntityId, taskType, config);
    }

    @Override
    public Model parsePersistedConfig(String inferenceEntityId, TaskType taskType, Map<String, Object> config) {
        Map<String, Object> serviceSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.SERVICE_SETTINGS);
        Map<String, Object> taskSettingsMap = removeFromMap(config, ModelConfigurations.TASK_SETTINGS);

        String modelId = (String) serviceSettingsMap.get(ElasticsearchInternalServiceSettings.MODEL_ID);
        if (modelId == null) {
            throw new IllegalArgumentException("Error parsing request config, model id is missing");
        }

        if (MULTILINGUAL_E5_SMALL_VALID_IDS.contains(modelId)) {
            return new MultilingualE5SmallModel(
                inferenceEntityId,
                taskType,
                NAME,
                new MultilingualE5SmallInternalServiceSettings(ElasticsearchInternalServiceSettings.fromPersistedMap(serviceSettingsMap))
            );
        } else if (ElserModels.isValidModel(modelId)) {
            return new ElserInternalModel(
                inferenceEntityId,
                taskType,
                NAME,
                new ElserInternalServiceSettings(ElasticsearchInternalServiceSettings.fromPersistedMap(serviceSettingsMap)),
                ElserMlNodeTaskSettings.DEFAULT
            );
        } else {
            return createCustomElandModel(
                inferenceEntityId,
                taskType,
                serviceSettingsMap,
                taskSettingsMap,
                ConfigurationParseContext.PERSISTENT
            );
        }
    }

    @Override
    public void checkModelConfig(Model model, ActionListener<Model> listener) {
        if (model instanceof CustomElandEmbeddingModel elandModel && elandModel.getTaskType() == TaskType.TEXT_EMBEDDING) {
            // At this point the inference endpoint configuration has not been persisted yet, if we attempt to do inference using the
            // inference id we'll get an error because the trained model code needs to use the persisted inference endpoint to retrieve the
            // model id. To get around this we'll have the getEmbeddingSize() method use the model id instead of inference id. So we need
            // to create a temporary model that overrides the inference id with the model id.
            var temporaryModelWithModelId = new CustomElandEmbeddingModel(
                elandModel.getServiceSettings().modelId(),
                elandModel.getTaskType(),
                elandModel.getConfigurations().getService(),
                elandModel.getServiceSettings()
            );

            ServiceUtils.getEmbeddingSize(
                temporaryModelWithModelId,
                this,
                listener.delegateFailureAndWrap((l, size) -> l.onResponse(updateModelWithEmbeddingDetails(elandModel, size)))
            );
        } else {
            listener.onResponse(model);
        }
    }

    private static CustomElandEmbeddingModel updateModelWithEmbeddingDetails(CustomElandEmbeddingModel model, int embeddingSize) {
        CustomElandInternalTextEmbeddingServiceSettings serviceSettings = new CustomElandInternalTextEmbeddingServiceSettings(
            model.getServiceSettings().getNumAllocations(),
            model.getServiceSettings().getNumThreads(),
            model.getServiceSettings().modelId(),
            model.getServiceSettings().getAdaptiveAllocationsSettings(),
            embeddingSize,
            model.getServiceSettings().similarity(),
            model.getServiceSettings().elementType()
        );

        return new CustomElandEmbeddingModel(
            model.getInferenceEntityId(),
            model.getTaskType(),
            model.getConfigurations().getService(),
            serviceSettings
        );
    }

    @Override
    public void infer(
        Model model,
        @Nullable String query,
        List<String> input,
        boolean stream,
        Map<String, Object> taskSettings,
        InputType inputType,
        TimeValue timeout,
        ActionListener<InferenceServiceResults> listener
    ) {
        var taskType = model.getConfigurations().getTaskType();
        if (TaskType.TEXT_EMBEDDING.equals(taskType)) {
            inferTextEmbedding(model, input, inputType, timeout, listener);
        } else if (TaskType.RERANK.equals(taskType)) {
            inferRerank(model, query, input, inputType, timeout, taskSettings, listener);
        } else if (TaskType.SPARSE_EMBEDDING.equals(taskType)) {
            inferSparseEmbedding(model, input, inputType, timeout, listener);
        } else {
            throw new ElasticsearchStatusException(TaskType.unsupportedTaskTypeErrorMsg(taskType, NAME), RestStatus.BAD_REQUEST);
        }
    }

    public void inferTextEmbedding(
        Model model,
        List<String> inputs,
        InputType inputType,
        TimeValue timeout,
        ActionListener<InferenceServiceResults> listener
    ) {
        var request = buildInferenceRequest(
            model.getConfigurations().getInferenceEntityId(),
            TextEmbeddingConfigUpdate.EMPTY_INSTANCE,
            inputs,
            inputType,
            timeout,
            false
        );

        client.execute(
            InferModelAction.INSTANCE,
            request,
            listener.delegateFailureAndWrap(
                (l, inferenceResult) -> l.onResponse(InferenceTextEmbeddingFloatResults.of(inferenceResult.getInferenceResults()))
            )
        );
    }

    public void inferSparseEmbedding(
        Model model,
        List<String> inputs,
        InputType inputType,
        TimeValue timeout,
        ActionListener<InferenceServiceResults> listener
    ) {
        var request = buildInferenceRequest(
            model.getConfigurations().getInferenceEntityId(),
            TextExpansionConfigUpdate.EMPTY_UPDATE,
            inputs,
            inputType,
            timeout,
            false
        );

        client.execute(
            InferModelAction.INSTANCE,
            request,
            listener.delegateFailureAndWrap(
                (l, inferenceResult) -> l.onResponse(SparseEmbeddingResults.of(inferenceResult.getInferenceResults()))
            )
        );
    }

    public void inferRerank(
        Model model,
        String query,
        List<String> inputs,
        InputType inputType,
        TimeValue timeout,
        Map<String, Object> requestTaskSettings,
        ActionListener<InferenceServiceResults> listener
    ) {
        var request = buildInferenceRequest(
            model.getConfigurations().getInferenceEntityId(),
            new TextSimilarityConfigUpdate(query),
            inputs,
            inputType,
            timeout,
            false
        );

        var modelSettings = (CustomElandRerankTaskSettings) model.getTaskSettings();
        var requestSettings = CustomElandRerankTaskSettings.fromMap(requestTaskSettings);
        Boolean returnDocs = CustomElandRerankTaskSettings.of(modelSettings, requestSettings).returnDocuments();

        Function<Integer, String> inputSupplier = returnDocs == Boolean.TRUE ? inputs::get : i -> null;

        client.execute(
            InferModelAction.INSTANCE,
            request,
            listener.delegateFailureAndWrap(
                (l, inferenceResult) -> l.onResponse(
                    textSimilarityResultsToRankedDocs(inferenceResult.getInferenceResults(), inputSupplier)
                )
            )
        );
    }

    public void chunkedInfer(
        Model model,
        List<String> input,
        Map<String, Object> taskSettings,
        InputType inputType,
        ChunkingOptions chunkingOptions,
        TimeValue timeout,
        ActionListener<List<ChunkedInferenceServiceResults>> listener
    ) {
        chunkedInfer(model, null, input, taskSettings, inputType, chunkingOptions, timeout, listener);
    }

    @Override
    public void chunkedInfer(
        Model model,
        @Nullable String query,
        List<String> input,
        Map<String, Object> taskSettings,
        InputType inputType,
        ChunkingOptions chunkingOptions,
        TimeValue timeout,
        ActionListener<List<ChunkedInferenceServiceResults>> listener
    ) {
        if ((TaskType.TEXT_EMBEDDING.equals(model.getTaskType()) || TaskType.SPARSE_EMBEDDING.equals(model.getTaskType())) == false) {
            listener.onFailure(
                new ElasticsearchStatusException(TaskType.unsupportedTaskTypeErrorMsg(model.getTaskType(), NAME), RestStatus.BAD_REQUEST)
            );
            return;
        }

        var configUpdate = chunkingOptions != null
            ? new TokenizationConfigUpdate(chunkingOptions.windowSize(), chunkingOptions.span())
            : new TokenizationConfigUpdate(null, null);

        var request = buildInferenceRequest(
            model.getConfigurations().getInferenceEntityId(),
            configUpdate,
            input,
            inputType,
            timeout,
            true
        );

        client.execute(
            InferModelAction.INSTANCE,
            request,
            listener.delegateFailureAndWrap(
                (l, inferenceResult) -> l.onResponse(translateToChunkedResults(inferenceResult.getInferenceResults()))
            )
        );
    }

    private static List<ChunkedInferenceServiceResults> translateToChunkedResults(List<InferenceResults> inferenceResults) {
        var translated = new ArrayList<ChunkedInferenceServiceResults>();

        for (var inferenceResult : inferenceResults) {
            translated.add(translateToChunkedResult(inferenceResult));
        }

        return translated;
    }

    private static ChunkedInferenceServiceResults translateToChunkedResult(InferenceResults inferenceResult) {
        if (inferenceResult instanceof MlChunkedTextEmbeddingFloatResults mlChunkedResult) {
            return InferenceChunkedTextEmbeddingFloatResults.ofMlResults(mlChunkedResult);
        } else if (inferenceResult instanceof MlChunkedTextExpansionResults mlChunkedResult) {
            return InferenceChunkedSparseEmbeddingResults.ofMlResult(mlChunkedResult);
        } else if (inferenceResult instanceof ErrorInferenceResults error) {
            return new ErrorChunkedInferenceResults(error.getException());
        } else {
            throw createInvalidChunkedResultException(MlChunkedTextEmbeddingFloatResults.NAME, inferenceResult.getWriteableName());
        }
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersions.V_8_14_0;
    }

    @Override
    public String name() {
        return NAME;
    }

    private RankedDocsResults textSimilarityResultsToRankedDocs(
        List<? extends InferenceResults> results,
        Function<Integer, String> inputSupplier
    ) {
        List<RankedDocsResults.RankedDoc> rankings = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            var result = results.get(i);
            if (result instanceof org.elasticsearch.xpack.core.ml.inference.results.TextSimilarityInferenceResults similarity) {
                rankings.add(new RankedDocsResults.RankedDoc(i, (float) similarity.score(), inputSupplier.apply(i)));
            } else if (result instanceof org.elasticsearch.xpack.core.ml.inference.results.ErrorInferenceResults errorResult) {
                if (errorResult.getException() instanceof ElasticsearchStatusException statusException) {
                    throw statusException;
                } else {
                    throw new ElasticsearchStatusException(
                        "Received error inference result.",
                        RestStatus.INTERNAL_SERVER_ERROR,
                        errorResult.getException()
                    );
                }
            } else {
                throw new IllegalArgumentException(
                    "Received invalid inference result, of type "
                        + result.getClass().getName()
                        + " but expected TextSimilarityInferenceResults."
                );
            }
        }

        Collections.sort(rankings);
        return new RankedDocsResults(rankings);
    }
}
