package io.javaoperatorsdk.operator;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.config.ConfigurationService;
import io.javaoperatorsdk.operator.api.config.ControllerConfiguration;
import io.javaoperatorsdk.operator.processing.CustomResourceCache;
import io.javaoperatorsdk.operator.processing.DefaultEventHandler;
import io.javaoperatorsdk.operator.processing.EventDispatcher;
import io.javaoperatorsdk.operator.processing.event.DefaultEventSourceManager;
import io.javaoperatorsdk.operator.processing.event.internal.CustomResourceEventSource;
import io.javaoperatorsdk.operator.processing.retry.GenericRetry;
import io.javaoperatorsdk.operator.processing.retry.Retry;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("rawtypes")
public class Operator {

  private static final Logger log = LoggerFactory.getLogger(Operator.class);
  private final KubernetesClient k8sClient;
  private final ConfigurationService configurationService;

  public Operator(KubernetesClient k8sClient, ConfigurationService configurationService) {
    this.k8sClient = k8sClient;
    this.configurationService = configurationService;
  }

  public <R extends CustomResource> void register(ResourceController<R> controller)
      throws OperatorException {
    register(controller, null);
  }

  public <R extends CustomResource> void register(
      ResourceController<R> controller, ControllerConfiguration<R> configuration)
      throws OperatorException {
    final var existing = configurationService.getConfigurationFor(controller);
    if (existing == null) {
      log.warn(
          "Skipping registration of {} controller named {} because its configuration cannot be found.\n"
              + "Known controllers are: {}",
          controller.getClass().getCanonicalName(),
          ControllerUtils.getNameFor(controller),
          configurationService.getKnownControllerNames());
    } else {
      if (configuration == null) {
        configuration = existing;
      }
      final var retry = GenericRetry.fromConfiguration(configuration.getRetryConfiguration());
      final var targetNamespaces = configuration.getNamespaces().toArray(new String[] {});
      registerController(controller, configuration.watchAllNamespaces(), retry, targetNamespaces);
    }
  }

  @SuppressWarnings("rawtypes")
  private <R extends CustomResource> void registerController(
      ResourceController<R> controller,
      boolean watchAllNamespaces,
      Retry retry,
      String... targetNamespaces)
      throws OperatorException {
    final var configuration = configurationService.getConfigurationFor(controller);
    Class<R> resClass = configuration.getCustomResourceClass();
    String finalizer = configuration.getFinalizer();
    MixedOperation client = k8sClient.customResources(resClass);
    EventDispatcher eventDispatcher =
        new EventDispatcher(
            controller, finalizer, new EventDispatcher.CustomResourceFacade(client));

    CustomResourceCache customResourceCache = new CustomResourceCache();
    DefaultEventHandler defaultEventHandler =
        new DefaultEventHandler(
            customResourceCache, eventDispatcher, controller.getClass().getName(), retry);
    DefaultEventSourceManager eventSourceManager =
        new DefaultEventSourceManager(defaultEventHandler, retry != null);
    defaultEventHandler.setEventSourceManager(eventSourceManager);
    eventDispatcher.setEventSourceManager(eventSourceManager);

    controller.init(eventSourceManager);
    CustomResourceEventSource customResourceEventSource =
        createCustomResourceEventSource(
            client,
            customResourceCache,
            watchAllNamespaces,
            targetNamespaces,
            defaultEventHandler,
            configuration.isGenerationAware(),
            finalizer);
    eventSourceManager.registerCustomResourceEventSource(customResourceEventSource);

    log.info(
        "Registered Controller: '{}' for CRD: '{}' for namespaces: {}",
        controller.getClass().getSimpleName(),
        resClass,
        targetNamespaces.length == 0
            ? "[all/client namespace]"
            : Arrays.toString(targetNamespaces));
  }

  private CustomResourceEventSource createCustomResourceEventSource(
      MixedOperation client,
      CustomResourceCache customResourceCache,
      boolean watchAllNamespaces,
      String[] targetNamespaces,
      DefaultEventHandler defaultEventHandler,
      boolean generationAware,
      String finalizer) {
    CustomResourceEventSource customResourceEventSource =
        watchAllNamespaces
            ? CustomResourceEventSource.customResourceEventSourceForAllNamespaces(
                customResourceCache, client, generationAware, finalizer)
            : CustomResourceEventSource.customResourceEventSourceForTargetNamespaces(
                customResourceCache, client, targetNamespaces, generationAware, finalizer);

    customResourceEventSource.setEventHandler(defaultEventHandler);

    return customResourceEventSource;
  }
}
