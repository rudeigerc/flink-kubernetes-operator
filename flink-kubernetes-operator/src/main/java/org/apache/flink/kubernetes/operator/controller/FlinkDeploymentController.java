/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.kubernetes.operator.controller;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.kubernetes.operator.config.DefaultConfig;
import org.apache.flink.kubernetes.operator.crd.FlinkDeployment;
import org.apache.flink.kubernetes.operator.crd.status.ReconciliationStatus;
import org.apache.flink.kubernetes.operator.exception.InvalidDeploymentException;
import org.apache.flink.kubernetes.operator.exception.ReconciliationException;
import org.apache.flink.kubernetes.operator.reconciler.BaseReconciler;
import org.apache.flink.kubernetes.operator.reconciler.JobReconciler;
import org.apache.flink.kubernetes.operator.reconciler.SessionReconciler;
import org.apache.flink.kubernetes.operator.utils.FlinkUtils;
import org.apache.flink.kubernetes.operator.validation.FlinkDeploymentValidator;
import org.apache.flink.kubernetes.utils.Constants;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusHandler;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.RetryInfo;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/** Controller that runs the main reconcile loop for Flink deployments. */
@ControllerConfiguration(generationAwareEventProcessing = false)
public class FlinkDeploymentController
        implements Reconciler<FlinkDeployment>,
                ErrorStatusHandler<FlinkDeployment>,
                EventSourceInitializer<FlinkDeployment> {
    private static final Logger LOG = LoggerFactory.getLogger(FlinkDeploymentController.class);

    private final KubernetesClient kubernetesClient;

    private final String operatorNamespace;

    private final FlinkDeploymentValidator validator;
    private final JobReconciler jobReconciler;
    private final SessionReconciler sessionReconciler;
    private final DefaultConfig defaultConfig;

    public FlinkDeploymentController(
            DefaultConfig defaultConfig,
            KubernetesClient kubernetesClient,
            String operatorNamespace,
            FlinkDeploymentValidator validator,
            JobReconciler jobReconciler,
            SessionReconciler sessionReconciler) {
        this.defaultConfig = defaultConfig;
        this.kubernetesClient = kubernetesClient;
        this.operatorNamespace = operatorNamespace;
        this.validator = validator;
        this.jobReconciler = jobReconciler;
        this.sessionReconciler = sessionReconciler;
    }

    @Override
    public DeleteControl cleanup(FlinkDeployment flinkApp, Context context) {
        LOG.info("Stopping cluster {}", flinkApp.getMetadata().getName());
        return getReconciler(flinkApp)
                .shutdownAndDelete(
                        operatorNamespace,
                        flinkApp,
                        context,
                        FlinkUtils.getEffectiveConfig(flinkApp, defaultConfig.getFlinkConfig()));
    }

    @Override
    public UpdateControl<FlinkDeployment> reconcile(FlinkDeployment flinkApp, Context context) {
        LOG.info("Reconciling {}", flinkApp.getMetadata().getName());

        Optional<String> validationError = validator.validate(flinkApp);
        if (validationError.isPresent()) {
            LOG.error("Reconciliation failed: " + validationError.get());
            updateForReconciliationError(flinkApp, validationError.get());
            return UpdateControl.updateStatus(flinkApp);
        }

        Configuration effectiveConfig =
                FlinkUtils.getEffectiveConfig(flinkApp, defaultConfig.getFlinkConfig());
        try {
            UpdateControl<FlinkDeployment> updateControl =
                    getReconciler(flinkApp)
                            .reconcile(operatorNamespace, flinkApp, context, effectiveConfig);
            updateForReconciliationSuccess(flinkApp);
            return updateControl;
        } catch (InvalidDeploymentException ide) {
            LOG.error("Reconciliation failed", ide);
            updateForReconciliationError(flinkApp, ide.getMessage());
            return UpdateControl.updateStatus(flinkApp);
        } catch (Exception e) {
            throw new ReconciliationException(e);
        }
    }

    private BaseReconciler getReconciler(FlinkDeployment flinkDeployment) {
        return flinkDeployment.getSpec().getJob() == null ? sessionReconciler : jobReconciler;
    }

    private void updateForReconciliationSuccess(FlinkDeployment flinkApp) {
        ReconciliationStatus reconciliationStatus = flinkApp.getStatus().getReconciliationStatus();
        reconciliationStatus.setSuccess(true);
        reconciliationStatus.setError(null);
        reconciliationStatus.setLastReconciledSpec(flinkApp.getSpec());
    }

    private void updateForReconciliationError(FlinkDeployment flinkApp, String err) {
        ReconciliationStatus reconciliationStatus = flinkApp.getStatus().getReconciliationStatus();
        reconciliationStatus.setSuccess(false);
        reconciliationStatus.setError(err);
    }

    @Override
    public List<EventSource> prepareEventSources(
            EventSourceContext<FlinkDeployment> eventSourceContext) {
        // reconcile when job manager deployment is ready
        SharedIndexInformer<Deployment> deploymentInformer =
                kubernetesClient
                        .apps()
                        .deployments()
                        .inAnyNamespace()
                        .withLabel(Constants.LABEL_TYPE_KEY, Constants.LABEL_TYPE_NATIVE_TYPE)
                        .withLabel(
                                Constants.LABEL_COMPONENT_KEY,
                                Constants.LABEL_COMPONENT_JOB_MANAGER)
                        .runnableInformer(0);
        return List.of(
                new InformerEventSource<>(
                        deploymentInformer, Mappers.fromLabel(Constants.LABEL_APP_KEY)));
    }

    @Override
    public Optional<FlinkDeployment> updateErrorStatus(
            FlinkDeployment flinkApp, RetryInfo retryInfo, RuntimeException e) {
        LOG.warn(
                "attempt count: {}, last attempt: {}",
                retryInfo.getAttemptCount(),
                retryInfo.isLastAttempt());

        updateForReconciliationError(
                flinkApp,
                (e instanceof ReconciliationException) ? e.getCause().toString() : e.toString());
        return Optional.of(flinkApp);
    }
}