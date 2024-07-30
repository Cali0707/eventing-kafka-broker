/*
 * Copyright © 2018 Knative Authors (knative-dev@googlegroups.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.knative.eventing.kafka.broker.receiver.main;

import static dev.knative.eventing.kafka.broker.core.utils.Logging.keyValue;

import dev.knative.eventing.kafka.broker.core.ReactiveProducerFactory;
import dev.knative.eventing.kafka.broker.core.eventbus.ContractMessageCodec;
import dev.knative.eventing.kafka.broker.core.eventbus.ContractPublisher;
import dev.knative.eventing.kafka.broker.core.eventtype.EventType;
import dev.knative.eventing.kafka.broker.core.features.FeaturesConfig;
import dev.knative.eventing.kafka.broker.core.file.FileWatcher;
import dev.knative.eventing.kafka.broker.core.metrics.Metrics;
import dev.knative.eventing.kafka.broker.core.oidc.OIDCDiscoveryConfig;
import dev.knative.eventing.kafka.broker.core.reconciler.impl.ResourcesReconcilerMessageHandler;
import dev.knative.eventing.kafka.broker.core.tracing.TracingConfig;
import dev.knative.eventing.kafka.broker.core.utils.Configurations;
import dev.knative.eventing.kafka.broker.core.utils.Shutdown;
import io.cloudevents.kafka.CloudEventSerializer;
import io.cloudevents.kafka.PartitionKeyExtensionInterceptor;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    static {
        if (System.getProperty("logback.configurationFile") == null
                || System.getProperty("logback.configurationFile").isEmpty()) {
            System.setProperty("logback.configurationFile", "/etc/logging/config.xml");
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * Start receiver.
     *
     * @param args command line arguments.
     */
    public static void start(final String[] args, final ReactiveProducerFactory kafkaProducerFactory)
            throws IOException, ExecutionException, InterruptedException {
        ReceiverEnv env = new ReceiverEnv(System::getenv);

        OpenTelemetrySdk openTelemetry =
                TracingConfig.fromDir(env.getConfigTracingPath()).setup();

        FeaturesConfig featuresConfig = new FeaturesConfig(env.getConfigFeaturesPath());

        // Read producer properties and override some defaults
        Properties producerConfigs = Configurations.readPropertiesSync(env.getProducerConfigFilePath());
        producerConfigs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerConfigs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, CloudEventSerializer.class);
        producerConfigs.put(
                ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, PartitionKeyExtensionInterceptor.class.getName());

        logger.info("Starting Receiver {}", keyValue("env", env));

        // Start Vertx
        Vertx vertx = Vertx.vertx(new VertxOptions()
                .setMetricsOptions(Metrics.getOptions(env))
                .setTracingOptions(new OpenTelemetryOptions(openTelemetry)));

        logger.info("Created vertx");

        // Register Contract message codec
        ContractMessageCodec.register(vertx.eventBus());

        logger.info("Registered message codec");

        logger.info("about to read server properties from file {}", env.getHttpServerConfigFilePath());
        // Read http server configuration and merge it with port from env
        HttpServerOptions httpServerOptions =
                new HttpServerOptions(Configurations.readPropertiesAsJsonSync(env.getHttpServerConfigFilePath()));

        logger.info("Read http server properties from file {}", env.getHttpServerConfigFilePath());
        httpServerOptions.setPort(env.getIngressPort());
        httpServerOptions.setTracingPolicy(TracingPolicy.PROPAGATE);

        logger.info("created http server options");

        // Read https server configuration and merge it with port from env
        HttpServerOptions httpsServerOptions =
                new HttpServerOptions(Configurations.readPropertiesAsJsonSync(env.getHttpServerConfigFilePath()));

        logger.info("Read https server properties from file {}", env.getHttpServerConfigFilePath());

        // Set the TLS port to a different port so that they don't have conflicts
        httpsServerOptions.setPort(env.getIngressTLSPort());
        httpsServerOptions.setTracingPolicy(TracingPolicy.PROPAGATE);

        logger.info("created https server options");

        // Setup OIDC discovery config
        OIDCDiscoveryConfig oidcDiscoveryConfig = null;
        try {
            logger.info("building OIDC config");
            oidcDiscoveryConfig = OIDCDiscoveryConfig.build(vertx)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get();
        } catch (Exception ex) {
            if (featuresConfig.isAuthenticationOIDC()) {
                logger.error("Could not load OIDC config while OIDC authentication feature is enabled.");
                throw ex;
            } else {
                logger.warn(
                        "Could not load OIDC configuration. This will lead to problems, when the {} flag will be enabled later",
                        FeaturesConfig.KEY_AUTHENTICATION_OIDC);
            }
        }


        final var kubernetesClient = new KubernetesClientBuilder().build();
        logger.info("built kubeclient");
        final SharedInformerFactory sharedInformerFactory = kubernetesClient.informers();
        final var eventTypeClient = kubernetesClient.resources(EventType.class);
        SharedIndexInformer<EventType> eventTypeInformer = null;
        try {
            eventTypeInformer = sharedInformerFactory.sharedIndexInformerFor(
                    EventType.class, 30 * 1000L); // refresh every 30 seconds
            sharedInformerFactory.startAllRegisteredInformers().get(5, TimeUnit.SECONDS);
            logger.info("started informers");
        } catch (InterruptedException | TimeoutException interruptedException) {
            logger.warn(
                    "failed to start informers, this will lead to unnecessary POST requests for eventtype autocreate");
        } catch (Exception informerException) {
            logger.warn(
                    "the data-plane does not have sufficient permissions to list/watch eventtypes. This will lead to unnecessary CREATE requests if eventtype-auto-create is enabled",
                    informerException);
        }

        // Configure the verticle to deploy and the deployment options

        try {
            final Supplier<Verticle> receiverVerticleFactory = new ReceiverVerticleFactory(
                    env,
                    producerConfigs,
                    Metrics.getRegistry(),
                    httpServerOptions,
                    httpsServerOptions,
                    kafkaProducerFactory,
                    eventTypeClient,
                    eventTypeInformer,
                    vertx,
                    oidcDiscoveryConfig);
            DeploymentOptions deploymentOptions =
                    new DeploymentOptions().setInstances(Runtime.getRuntime().availableProcessors());
            // Deploy the receiver verticles
            vertx.deployVerticle(receiverVerticleFactory, deploymentOptions)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(env.getWaitStartupSeconds(), TimeUnit.SECONDS);
            logger.info("Receiver started");

            ContractPublisher publisher =
                    new ContractPublisher(vertx.eventBus(), ResourcesReconcilerMessageHandler.ADDRESS);
            File file = new File(env.getDataPlaneConfigFilePath());
            FileWatcher fileWatcher = new FileWatcher(file, () -> publisher.updateContract(file));
            fileWatcher.start();

            var closeables =
                    new ArrayList<>(Arrays.asList(publisher, fileWatcher, openTelemetry.getSdkTracerProvider()));

            if (eventTypeInformer != null) {
                closeables.add(eventTypeInformer);
            }

            // Register shutdown hook for graceful shutdown.
            Shutdown.registerHook(vertx, closeables.toArray(new AutoCloseable[0]));

        } catch (final Exception ex) {
            logger.error("Failed to startup the receiver", ex);
            Shutdown.closeVertxSync(vertx);
            System.exit(1);
        }
    }
}
