/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.cohere;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.inference.InferenceServiceResults;
import org.elasticsearch.inference.InputType;
import org.elasticsearch.inference.Model;
import org.elasticsearch.inference.ModelConfigurations;
import org.elasticsearch.inference.ModelSecrets;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xpack.inference.common.SimilarityMeasure;
import org.elasticsearch.xpack.inference.external.action.cohere.CohereActionCreator;
import org.elasticsearch.xpack.inference.external.http.sender.HttpRequestSenderFactory;
import org.elasticsearch.xpack.inference.services.SenderService;
import org.elasticsearch.xpack.inference.services.ServiceComponents;
import org.elasticsearch.xpack.inference.services.ServiceUtils;
import org.elasticsearch.xpack.inference.services.cohere.embeddings.CohereEmbeddingsModel;
import org.elasticsearch.xpack.inference.services.cohere.embeddings.CohereEmbeddingsServiceSettings;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.xpack.inference.services.ServiceUtils.createInvalidModelException;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.parsePersistedConfigErrorMsg;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.removeFromMapOrThrowIfNull;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.throwIfNotEmptyMap;

public class CohereService extends SenderService {
    public static final String NAME = "cohere";

    public CohereService(SetOnce<HttpRequestSenderFactory> factory, SetOnce<ServiceComponents> serviceComponents) {
        super(factory, serviceComponents);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CohereModel parseRequestConfig(
        String modelId,
        TaskType taskType,
        Map<String, Object> config,
        Set<String> platformArchitectures
    ) {
        Map<String, Object> serviceSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.SERVICE_SETTINGS);
        Map<String, Object> taskSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.TASK_SETTINGS);

        CohereModel model = createModel(
            modelId,
            taskType,
            serviceSettingsMap,
            taskSettingsMap,
            serviceSettingsMap,
            TaskType.unsupportedTaskTypeErrorMsg(taskType, NAME)
        );

        throwIfNotEmptyMap(config, NAME);
        throwIfNotEmptyMap(serviceSettingsMap, NAME);
        throwIfNotEmptyMap(taskSettingsMap, NAME);

        return model;
    }

    private static CohereModel createModel(
        String modelId,
        TaskType taskType,
        Map<String, Object> serviceSettings,
        Map<String, Object> taskSettings,
        @Nullable Map<String, Object> secretSettings,
        String failureMessage
    ) {
        return switch (taskType) {
            case TEXT_EMBEDDING -> new CohereEmbeddingsModel(modelId, taskType, NAME, serviceSettings, taskSettings, secretSettings);
            default -> throw new ElasticsearchStatusException(failureMessage, RestStatus.BAD_REQUEST);
        };
    }

    @Override
    public CohereModel parsePersistedConfigWithSecrets(
        String modelId,
        TaskType taskType,
        Map<String, Object> config,
        Map<String, Object> secrets
    ) {
        Map<String, Object> serviceSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.SERVICE_SETTINGS);
        Map<String, Object> taskSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.TASK_SETTINGS);
        Map<String, Object> secretSettingsMap = removeFromMapOrThrowIfNull(secrets, ModelSecrets.SECRET_SETTINGS);

        return createModel(
            modelId,
            taskType,
            serviceSettingsMap,
            taskSettingsMap,
            secretSettingsMap,
            parsePersistedConfigErrorMsg(modelId, NAME)
        );
    }

    @Override
    public CohereModel parsePersistedConfig(String modelId, TaskType taskType, Map<String, Object> config) {
        Map<String, Object> serviceSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.SERVICE_SETTINGS);
        Map<String, Object> taskSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.TASK_SETTINGS);

        return createModel(modelId, taskType, serviceSettingsMap, taskSettingsMap, null, parsePersistedConfigErrorMsg(modelId, NAME));
    }

    @Override
    public void doInfer(
        Model model,
        List<String> input,
        Map<String, Object> taskSettings,
        InputType inputType,
        ActionListener<InferenceServiceResults> listener
    ) {
        if (model instanceof CohereModel == false) {
            listener.onFailure(createInvalidModelException(model));
            return;
        }

        CohereModel cohereModel = (CohereModel) model;
        var actionCreator = new CohereActionCreator(getSender(), getServiceComponents());

        var action = cohereModel.accept(actionCreator, taskSettings, inputType);
        action.execute(input, listener);
    }

    /**
     * For text embedding models get the embedding size and
     * update the service settings.
     *
     * @param model The new model
     * @param listener The listener
     */
    @Override
    public void checkModelConfig(Model model, ActionListener<Model> listener) {
        if (model instanceof CohereEmbeddingsModel embeddingsModel) {
            ServiceUtils.getEmbeddingSize(
                model,
                this,
                listener.delegateFailureAndWrap((l, size) -> l.onResponse(updateModelWithEmbeddingDetails(embeddingsModel, size)))
            );
        } else {
            listener.onResponse(model);
        }
    }

    private CohereEmbeddingsModel updateModelWithEmbeddingDetails(CohereEmbeddingsModel model, int embeddingSize) {
        CohereEmbeddingsServiceSettings serviceSettings = new CohereEmbeddingsServiceSettings(
            new CohereServiceSettings(
                model.getServiceSettings().getCommonSettings().getUri(),
                SimilarityMeasure.DOT_PRODUCT,
                embeddingSize,
                model.getServiceSettings().getCommonSettings().getMaxInputTokens(),
                model.getServiceSettings().getCommonSettings().getModel()
            ),
            model.getServiceSettings().getEmbeddingType()
        );

        return new CohereEmbeddingsModel(model, serviceSettings);
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersions.ML_INFERENCE_REQUEST_INPUT_TYPE_UNSPECIFIED_ADDED;
    }
}