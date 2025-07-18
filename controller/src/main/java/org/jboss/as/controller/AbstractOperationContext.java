/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.controller;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ALLOW_RESOURCE_SERVICE_RESTART;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ATTRIBUTE_VALUE_WRITTEN_NOTIFICATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CANCELLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.LEVEL;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATION_HEADERS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATION_REQUIRES_RELOAD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATION_REQUIRES_RESTART;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PROFILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESOURCE_ADDED_NOTIFICATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESOURCE_REMOVED_NOTIFICATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESPONSE_HEADERS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ROLLBACK_ON_RUNTIME_FAILURE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ROLLED_BACK;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ROLLOUT_PLAN;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RUNTIME_UPDATE_SKIPPED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_GROUPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUCCESS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.UNDEFINE_ATTRIBUTE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.WARNING;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.WARNINGS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION;
import static org.jboss.as.controller.logging.ControllerLogger.MGMT_OP_LOGGER;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.jboss.as.controller.ConfigurationChangesCollector.ConfigurationChange;
import org.jboss.as.controller.access.Environment;
import org.jboss.as.controller.audit.AuditLogger;
import org.jboss.as.controller.capability.registry.RuntimeCapabilityRegistry;
import org.jboss.as.controller.client.MessageSeverity;
import org.jboss.as.controller.client.OperationResponse;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.notification.Notification;
import org.jboss.as.controller.notification.NotificationSupport;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.operations.global.ReadResourceHandler;
import org.jboss.as.controller.persistence.ConfigurationPersistenceException;
import org.jboss.as.controller.persistence.ConfigurationPersister;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.NotificationEntry;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.core.security.AccessMechanism;
import org.jboss.as.protocol.StreamUtils;
import org.jboss.as.version.Stability;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceTarget;
import org.wildfly.common.Assert;
import org.wildfly.security.auth.server.SecurityIdentity;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * Base class for operation context implementations.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
@SuppressWarnings("deprecation")
abstract class AbstractOperationContext implements OperationContext, AutoCloseable {

    private static final Set<String> NON_COPIED_HEADERS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(ALLOW_RESOURCE_SERVICE_RESTART,
                    ROLLBACK_ON_RUNTIME_FAILURE, ROLLOUT_PLAN)));
    private static final Set<ControlledProcessState.State> RUNTIME_LIMITED_STATES =
            EnumSet.of(ControlledProcessState.State.RELOAD_REQUIRED, ControlledProcessState.State.RESTART_REQUIRED);

    static final ThreadLocal<Thread> controllingThread = new ThreadLocal<Thread>();

    /** Thread that initiated execution of the overall operation for which this context is the whole or a part */
    final Thread initiatingThread;
    private final EnumMap<Stage, Deque<Step>> steps;
    private final ModelController.OperationTransactionControl transactionControl;
    final ControlledProcessState processState;
    final NotificationSupport notificationSupport;
    final OperationHeaders operationHeaders;
    private final boolean booting;
    private final ProcessType processType;
    private final Stability stability;
    private final RunningMode runningMode;
    private final Environment callEnvironment;
    private final ConfigurationChangesCollector configurationChangesCollector = ConfigurationChangesCollector.INSTANCE;
    // We only respect interruption on the way in; once we complete all steps
    // and begin
    // returning, any calls that can throw InterruptedException are converted to
    // an uninterruptible form. This is to ensure rollback changes are not
    // interrupted
    volatile boolean respectInterruption = true;

    /**
     * The notifications are stored when {@code emit(Notification)} is called and effectively
     * emitted at the end of the operation execution if it is successful
     */
    private final Queue<Notification> notifications;

    /**
     * Notifications descriptions are checked in {@code emit(Notification)} to use the resource registration
     * when the operation is performed on the resource [WFCORE-1007]
     * Similar to {@code notifications}, the eventual warnings will be buffered and actually logged
     * at the end of the operation execution if it is successful.
     */
    private final Queue<String> missingNotificationDescriptionWarnings;

    Stage currentStage = Stage.MODEL;

    ResultAction resultAction;
    /** Tracks whether we've detected cancellation */
    boolean cancelled;
    /** Currently executing step */
    Step activeStep;
    /** The step that acquired the write lock */
    Step lockStep;
    private final Supplier<SecurityIdentity> securityIdentitySupplier;
    /** Whether operation execution has begun; i.e. whether completeStep() has been called */
    private boolean executing;
    /** First response node provided to addStep  */
    ModelNode initialResponse;
    /** Operation provided to addStep along with initialResponse */
    ModelNode initialOperation;

    /** Operations that were added by the controller, before execution started */
    private final List<ModelNode> controllerOperations = new ArrayList<ModelNode>(2);
    private boolean auditLogged;
    private final AuditLogger auditLogger;
    private final ModelControllerImpl controller;
    private final OperationStepHandler extraValidationStepHandler;
    // protected by this
    private Map<String, OperationResponse.StreamEntry> responseStreams;

    private final Level WARNING_DEFAULT_LEVEL = Level.WARNING;
    /**
     * Resources modified by this context's operations. May be modified by ParallelBootOperationStepHandler which spawns threads,
     * so guard by itself.
     * If validation is to be skipped this will be {@code null}
     */
    private final Set<PathAddress> modifiedResourcesForModelValidation;


    enum ContextFlag {
        ROLLBACK_ON_FAIL, ALLOW_RESOURCE_SERVICE_RESTART,
    }

    AbstractOperationContext(final ProcessType processType,
                             final Stability stability,
                             final RunningMode runningMode,
                             final ModelController.OperationTransactionControl transactionControl,
                             final ControlledProcessState processState,
                             final boolean booting,
                             final AuditLogger auditLogger,
                             final NotificationSupport notificationSupport,
                             final ModelControllerImpl controller,
                             final boolean skipModelValidation,
                             final OperationStepHandler extraValidationStepHandler,
                             final OperationHeaders operationHeaders,
                             final Supplier<SecurityIdentity> securityIdentitySupplier) {
        this.processType = processType;
        this.stability = stability;
        this.runningMode = runningMode;
        this.transactionControl = transactionControl;
        this.processState = processState;
        this.booting = booting;
        this.auditLogger = auditLogger;
        this.notificationSupport = notificationSupport;
        this.notifications = new ConcurrentLinkedQueue<Notification>();
        this.missingNotificationDescriptionWarnings = new ConcurrentLinkedQueue<String>();
        this.controller = controller;
        steps = new EnumMap<Stage, Deque<Step>>(Stage.class);
        for (Stage stage : Stage.values()) {
            if (booting && stage == Stage.VERIFY) {
                // Use a concurrent structure as the parallel boot threads will
                // concurrently add steps
                steps.put(stage, new LinkedBlockingDeque<Step>());
            } else {
                steps.put(stage, new ArrayDeque<Step>());
            }
        }
        initiatingThread = Thread.currentThread();
        this.callEnvironment = new Environment(processState, processType);
        modifiedResourcesForModelValidation = skipModelValidation == false ?  new HashSet<PathAddress>() : null;
        this.extraValidationStepHandler = extraValidationStepHandler;
        this.operationHeaders = operationHeaders == null ? OperationHeaders.forInternalCall() : operationHeaders;
        this.securityIdentitySupplier = securityIdentitySupplier;
    }

    @Override
    public Stability getStability() {
        return this.stability;
    }

    /**
     * Internal access to the management model implementation.
     *
     * @return the management model
     */
    abstract ModelControllerImpl.ManagementModelImpl getManagementModel();

    /**
     * Internal helper to read a resource from a given management model.
     *
     * @param model        the management model to read from
     * @param address      the absolute path address
     * @param recursive    whether to read the model recursive or not
     * @return the resource
     */
    abstract Resource readResourceFromRoot(final ManagementModel model, final PathAddress address, final boolean recursive);

    @Override
    public boolean isBooting() {
        return booting;
    }

    @Override
    public void addStep(final OperationStepHandler step, final Stage stage) throws IllegalArgumentException {
        addStep(step, stage, false);
    }

    @Override
    public void addStep(final ModelNode operation, final OperationStepHandler step, final Stage stage)
            throws IllegalArgumentException {
        final ModelNode response = activeStep == null ? new ModelNode().setEmptyObject() : activeStep.response;
        addStep(response, operation, null, step, stage);
    }

    @Override
    public void addStep(final ModelNode operation, final OperationStepHandler step, final Stage stage, final boolean addFirst)
            throws IllegalArgumentException {
        final ModelNode response = activeStep == null ? new ModelNode().setEmptyObject() : activeStep.response;
        addStep(response, operation, null, null, step, stage, addFirst);
    }

    @Override
    public void addStep(final ModelNode response, final ModelNode operation, final OperationStepHandler step, final Stage stage)
            throws IllegalArgumentException {
        addStep(response, operation, null, step, stage);
    }

    @Override
    public void addStep(OperationStepHandler step, Stage stage, boolean addFirst) throws IllegalArgumentException {
        addStep(activeStep.response, activeStep.operation, activeStep.address, null, step, stage, addFirst);
    }

    @Override
    public void addModelStep(OperationDefinition stepDefinition, OperationStepHandler stepHandler, boolean addFirst) throws IllegalArgumentException {
        addStep(activeStep.response, activeStep.operation, activeStep.address, stepDefinition, stepHandler, Stage.MODEL, addFirst);
    }

    @Override
    public void addModelStep(ModelNode response, ModelNode operation, OperationDefinition stepDefinition, OperationStepHandler stepHandler, boolean addFirst) throws IllegalArgumentException {
        addStep(response, operation, null, stepDefinition, stepHandler, Stage.MODEL, addFirst);
    }

    void addStep(final ModelNode response, final ModelNode operation, final PathAddress address, final OperationStepHandler step,
            final Stage stage) throws IllegalArgumentException {
        addStep(response, operation, address, null, step, stage, false);
    }

    @Override
    public void addStep(ModelNode response, ModelNode operation, OperationStepHandler step, Stage stage, boolean addFirst)
            throws IllegalArgumentException {
        addStep(response, operation, null, null, step, stage, addFirst);
    }

    void addStep(final ModelNode response, final ModelNode operation, final PathAddress address, OperationDefinition stepDefinition, final OperationStepHandler step,
                 final Stage stage, boolean addFirst) throws IllegalArgumentException {

        assert isControllingThread();
        Assert.checkNotNullParam("response", response);
        Assert.checkNotNullParam("operation", operation);
        Assert.checkNotNullParam("step", step);
        Assert.checkNotNullParam("stage", stage);
        if (currentStage == Stage.DONE) {
            throw ControllerLogger.ROOT_LOGGER.operationAlreadyComplete();
        }
        if (stage.compareTo(currentStage) < 0) {
            throw ControllerLogger.ROOT_LOGGER.stageAlreadyComplete(stage);
        }
        if (stage == Stage.DOMAIN && !processType.isHostController()) {
            throw ControllerLogger.ROOT_LOGGER.invalidStage(stage, processType);
        }
        if (stage == Stage.DONE) {
            throw ControllerLogger.ROOT_LOGGER.invalidStepStage();
        }
        final PathAddress stepAddress = address != null ? address : PathAddress.pathAddress(operation.get(OP_ADDR));

        // Ignore runtime ops against profile resources on an HC
        if (stage == Stage.RUNTIME && !processType.isServer() && stepAddress.size() > 1 && PROFILE.equals(stepAddress.getElement(0).getKey())) {
            // Log this as it means we have an incorrect OSH
            ControllerLogger.ROOT_LOGGER.invalidRuntimeStageForProfile(operation.get(OP).asString(), stepAddress.toCLIStyleString(), stage, processType);
            return;
        }

        if (!booting && activeStep != null) {
            // Added steps inherit the caller type of their parent
            if (activeStep.operation.hasDefined(OPERATION_HEADERS)) {
                ModelNode activeHeaders = activeStep.operation.get(OPERATION_HEADERS);
                for (Property property : activeHeaders.asPropertyList()) {
                    String key = property.getName();
                    if (!NON_COPIED_HEADERS.contains(key)) {
                        operation.get(OPERATION_HEADERS, key).set(property.getValue());
                    }
                }
            }
        }

        final Deque<Step> deque = steps.get(stage);
        if (addFirst) {
            deque.addFirst(new Step(stepDefinition, step, response, operation, stepAddress));
        } else {
            deque.addLast(new Step(stepDefinition, step, response, operation, stepAddress));
        }

        if (!executing && stage == Stage.MODEL) {
            recordControllerOperation(operation);
            if (initialResponse == null) {
                initialResponse = response;
                initialOperation = operation;
            }
        }
    }

    void addBootStep(ParsedBootOp parsedBootOp) {
        addStep(parsedBootOp.response, parsedBootOp.operation, parsedBootOp.handler, Stage.MODEL);
        // If the op is controlling other ops (i.e. for parallel boot) then record those for audit logging
        for (ModelNode childOp : parsedBootOp.getChildOperations()) {
            recordControllerOperation(childOp);
        }
    }

    @Override
    public void addResponseWarning(final Level level, final String warning) {
        final ModelNode modelNodeWarning = new ModelNode(warning);
        this.addResponseWarning(level, modelNodeWarning);
    }

    @Override
    public void addResponseWarning(final Level level,final  ModelNode warning) {
        if(!isWarningLoggable(level)){
            return;
        }
        createWarning(level, warning);
    }

    private void createWarning(final Level level, final String warning){
        final ModelNode modelNodeWarning = new ModelNode(warning);
        createWarning(level,modelNodeWarning);
    }

    private void createWarning(final Level level, final ModelNode warning){

        final ModelNode warningEntry = new ModelNode();
        warningEntry.get(WARNING).set(warning);
        warningEntry.get(LEVEL).set(level.toString());
        final ModelNode operation = activeStep.operation;
        if(operation != null){
            final ModelNode operationEntry =  warningEntry.get(OP);
            operationEntry.get(OP_ADDR).set(operation.get(OP_ADDR));
            operationEntry.get(OP).set(operation.get(OP));
        }

        final ModelNode warnings = getResponseHeaders().get(WARNINGS);
        boolean unique = true;
        if (warnings.isDefined()) {
            // Don't repeat a warning. This is basically a secondary safeguard
            // against different steps for the same op ending up reporting the
            // same warning. List iteration is not efficient but > 1 warning
            // in an op is an edge case
            for (ModelNode existing : warnings.asList()) {
                if (existing.equals(warningEntry)) {
                    unique = false;
                    break;
                }
            }
        }
        if (unique) {
            warnings.add(warningEntry);
        }
    }

    private boolean isWarningLoggable(final Level level){
        Level thresholdLevel;
        String headerLevel = operationHeaders.getWarningLevel();
        if (headerLevel != null) {
            try {
                thresholdLevel = Level.parse(headerLevel);
            } catch(Exception e) {
                createWarning(Level.ALL, ControllerLogger.ROOT_LOGGER.couldntConvertWarningLevel(headerLevel));
                thresholdLevel = Level.ALL;
            }
        } else {
            thresholdLevel = WARNING_DEFAULT_LEVEL;
        }
        return thresholdLevel.intValue() <= level.intValue();
    }

    @Override
    public final ModelNode getFailureDescription() {
        return activeStep.response.get(FAILURE_DESCRIPTION);
    }

    @Override
    public final boolean hasFailureDescription() {
        return activeStep.response.has(FAILURE_DESCRIPTION);
    }

    @Override
    public final ModelNode getResponseHeaders() {
        return activeStep.response.get(RESPONSE_HEADERS);
    }

    @Override
    public void close() {
        this.activeStep = null;
        if (modifiedResourcesForModelValidation != null) {
            modifiedResourcesForModelValidation.clear();
        }
    }

    /**
     * Package-protected method used to initiate operation execution.
     * @return the result action
     */
    ResultAction executeOperation() {

        assert isControllingThread();
        try {
            /** Execution has begun */
            executing = true;

            processStages();

            if (resultAction == ResultAction.KEEP) {
                report(MessageSeverity.INFO, ControllerLogger.ROOT_LOGGER.operationSucceeded());
            } else {
                report(MessageSeverity.INFO, ControllerLogger.ROOT_LOGGER.operationRollingBack());
            }
        } catch (RuntimeException e) {
            handleUncaughtException(e);
            ControllerLogger.MGMT_OP_LOGGER.unexpectedOperationExecutionException(e, controllerOperations);
        } finally {
            // On failure close any attached response streams
            if (resultAction != ResultAction.KEEP && !isBooting()) {
                synchronized (this) {
                    if (responseStreams != null) {
                        int i = 0;
                        for (OperationResponse.StreamEntry is : responseStreams.values()) {
                            try {
                                is.getStream().close();
                            } catch (Exception e) {
                                ControllerLogger.MGMT_OP_LOGGER.debugf(e, "Failed closing stream at index %d", i);
                            }
                            i++;
                        }
                        responseStreams.clear();
                    }
                }
            }
        }


        return resultAction;
    }

    /** Opportunity to do required cleanup after an exception propagated all the way to {@link #executeOperation()}.*/
    void handleUncaughtException(RuntimeException e) {
    }

    @Override
    public final void completeStep(RollbackHandler rollbackHandler) {
        Assert.checkNotNullParam("rollbackHandler", rollbackHandler);
        if (rollbackHandler == RollbackHandler.NOOP_ROLLBACK_HANDLER) {
            completeStep(ResultHandler.NOOP_RESULT_HANDLER);
        } else {
            completeStep(new RollbackDelegatingResultHandler(rollbackHandler));
        }
        // we return and executeStep picks it up
    }

    @Override
    public final void completeStep(ResultHandler resultHandler) {
        Assert.checkNotNullParam("resultHandler", resultHandler);
        this.activeStep.setResultHandler(resultHandler);
        // we return and executeStep picks it up
    }

    @Override
    public final PathAddress getCurrentAddress() {
        assert activeStep != null;
        return activeStep.address;
    }

    @Override
    public final String getCurrentAddressValue() {
        PathAddress pa = getCurrentAddress();
        if (pa.size() == 0) {
            throw new IllegalStateException();
        }
        return pa.getLastElement().getValue();
    }

    @Override
    public final String getCurrentOperationName() {
        assert activeStep != null;
        ModelNode operation = activeStep.operation;

        assert operation != null;
        return operation.get(OP).asString();
    }

    @Override
    public final ModelNode getCurrentOperationParameter(final String parameterName) {
        return getCurrentOperationParameter(parameterName, true);
    }

    @Override
    public final ModelNode getCurrentOperationParameter(final String parameterName, boolean nullable) {
        if (isLegalParameterName(parameterName)) {
            assert activeStep != null;
            ModelNode operation = activeStep.operation;

            assert operation != null;
            if (!operation.has(parameterName) && nullable) {
                return null;
            } else {
                return operation.get(parameterName);
            }
        } else {
            throw ControllerLogger.ROOT_LOGGER.invalidParameterName(parameterName);
        }
    }

    private boolean isLegalParameterName(final String parameterName) {
        return !(parameterName.equals(OP) || parameterName.equals(OP_ADDR) || parameterName.equals(OPERATION_HEADERS));
    }

    /**
     * Notification that all steps in a stage have been executed.
     * <p>This default implementation always returns {@code true}.</p>
     * @param stage the stage that is completed. Will not be {@code null}
     *
     * @return {@code true} if execution should proceed to the next stage; {@code false} if execution should halt
     */
    boolean stageCompleted(Stage stage) {
        return true;
    }

    /**
     * If appropriate for this implementation, block waiting for the
     * {@link ModelControllerImpl#awaitContainerStability(long, java.util.concurrent.TimeUnit, boolean)} method to return,
     * ensuring that the controller's MSC ServiceContainer is settled and it is safe to proceed to service status verification.
     *
     * @throws InterruptedException if the thread is interrupted while blocking
     * @throws java.util.concurrent.TimeoutException if attaining container stability takes an unacceptable amount of time
     */
    abstract void awaitServiceContainerStability() throws InterruptedException, TimeoutException;

    /**
     * Create a persistence resource (if appropriate for this implementation) for use in persisting the configuration
     * model that results from this operation. If a resource is created, it should perform as much persistence work
     * as possible without modifying the official persistence store (i.e. the config file) in order to detect any
     * persistence issues.
     *
     * @return the persistence resource, or {@code null} if persistence is not supported
     *
     * @throws ConfigurationPersistenceException if there is a problem creating the persistence resource
     */
    abstract ConfigurationPersister.PersistenceResource createPersistenceResource() throws ConfigurationPersistenceException;

    /**
     * Notification that the operation is not going to proceed to normal completion.
     */
    abstract void operationRollingBack();

    /**
     * Release any locks held by the given step.
     *
     * @param step the step
     */
    abstract void releaseStepLocks(AbstractStep step);

    /**
     * Wait for completion of removal of any services removed by this context.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    abstract void waitForRemovals() throws InterruptedException, TimeoutException;

    /**
     * Gets whether any steps have taken actions that indicate a wish to write to the model or the service container.
     *
     * @return {@code true} if no
     */
    abstract boolean isReadOnly();

    /**
     * Gets a reference to the mutable ManagementResourceRegistration for the resource tree root.
     * <p>
     * This method validates that the caller has the necessary permissions to modify the model.
     *
     * @return the registration.
     */
    abstract ManagementResourceRegistration getRootResourceRegistrationForUpdate();


    /**
     * Gets whether the currently executing thread is allowed to control this operation context.
     *
     * @return {@code true} if the currently executing thread is allowed to control the context
     */
    boolean isControllingThread() {
        return Thread.currentThread() == initiatingThread || controllingThread.get() == initiatingThread;
    }

    /**
     * Log an audit record of this operation.
     */
    void logAuditRecord() {
        trackConfigurationChange();
        if (!auditLogged) {
            try {
                AccessAuditContext accessContext = SecurityActions.currentAccessAuditContext();
                SecurityIdentity identity = getSecurityIdentity();
                auditLogger.log(
                        isReadOnly(),
                        resultAction,
                        identity == null ? null : identity.getPrincipal().getName(),
                        accessContext == null ? null : accessContext.getDomainUuid(),
                        accessContext == null ? null : accessContext.getAccessMechanism(),
                        accessContext == null ? null : accessContext.getRemoteAddress(),
                        getModel(),
                        controllerOperations);
                auditLogged = true;
            } catch (Exception e) {
                ControllerLogger.MGMT_OP_LOGGER.failedToUpdateAuditLog(e);
            }
        }
    }

    /**
     * Record an operation added before execution began (i.e. added by the controller and not by a step)
     * @param operation the operation
     */
    private void recordControllerOperation(ModelNode operation) {
        controllerOperations.add(operation.clone()); // clone so we don't log op nodes mutated during execution
    }

    void recordWriteLock() {
        this.lockStep = activeStep.recordWriteLock();
    }

    void trackConfigurationChange() {
        if (!isBooting() && !isReadOnly() && configurationChangesCollector.trackAllowed()) {
            try {
                AccessAuditContext accessContext = SecurityActions.currentAccessAuditContext();
                SecurityIdentity identity = getSecurityIdentity();
                configurationChangesCollector.addConfigurationChanges(new ConfigurationChange(resultAction,
                        identity == null ? null : identity.getPrincipal().getName(),
                        accessContext == null ? null : accessContext.getDomainUuid(),
                        accessContext == null ? null : accessContext.getAccessMechanism(),
                        accessContext == null ? null : accessContext.getRemoteAddress(),
                        controllerOperations));
            } catch (Exception e) {
                ControllerLogger.MGMT_OP_LOGGER.failedToUpdateAuditLog(e);
            }
        }
    }

    abstract Resource getModel();

    /**
     * Indicates whether the capabilities associated with the resource addressed by the current step
     * require a reload or a restart before any Stage.RUNTIME execution can happen.
     * Should only be invoked if the overall process state requires reload/restart
     * and the step is not for a runtime-only operation or resource.
     *
     * @param step the step. Cannot be {@code null}
     * @return {@link org.jboss.as.controller.capability.registry.RuntimeCapabilityRegistry.RuntimeStatus} for the resource
     *          associated with {@code step}
     */
    CapabilityRegistry.RuntimeStatus getStepCapabilityStatus(Step step) {
        return RuntimeCapabilityRegistry.RuntimeStatus.NORMAL;
    }

    /**
     * Perform the work of processing the various OperationContext.Stage queues, and then the DONE stage.
     */
    private void processStages() {

        // Locate the next step to execute.
        ModelNode primaryResponse = null;
        do {
            Step step = steps.get(currentStage).pollFirst();
            if (step == null) {

                if (currentStage == Stage.MODEL && addModelValidationSteps()) {
                    continue;
                }
                // No steps remain in this stage; give subclasses a chance to check status
                // and approve moving to the next stage
                if (!tryStageCompleted(currentStage)) {
                    // Can't continue
                    resultAction = ResultAction.ROLLBACK;
                    executeResultHandlerPhase(null);
                    return;
                }
                // Proceed to the next stage
                if (currentStage.hasNext()) {
                    currentStage = currentStage.next();
                    if (currentStage == Stage.VERIFY) {
                        // a change was made to the runtime. Thus, we must wait
                        // for stability before resuming in to verify.
                        try {
                            awaitServiceContainerStability();
                        } catch (InterruptedException e) {
                            cancelled = true;
                            handleContainerStabilityFailure(primaryResponse, e);
                            executeResultHandlerPhase(null);
                            return;
                        }  catch (TimeoutException te) {
                            // The service container is in an unknown state; but we don't require restart
                            // because rollback may allow the container to stabilize. We force require-restart
                            // in the rollback handling if the container cannot stabilize (see OperationContextImpl.releaseStepLocks)
                            //processState.setRestartRequired();  // don't use our restartRequired() method as this is not reversible in rollback
                            handleContainerStabilityFailure(primaryResponse, te);
                            executeResultHandlerPhase(null);
                            return;
                        }
                    }
                }
            } else {
                // The response to the first step is what goes to the outside caller
                if (primaryResponse == null) {
                    primaryResponse = step.response;
                }
                // Execute the step, but make sure we always finalize any steps
                Throwable toThrow = null;
                // Whether to return after try/finally
                boolean exit = false;
                try {
                    CapabilityRegistry.RuntimeStatus stepStatus = getStepExecutionStatus(step);
                    if (stepStatus == RuntimeCapabilityRegistry.RuntimeStatus.NORMAL) {
                        executeStep(step);
                    } else {
                        String header = stepStatus == RuntimeCapabilityRegistry.RuntimeStatus.RESTART_REQUIRED
                                ? OPERATION_REQUIRES_RESTART : OPERATION_REQUIRES_RELOAD;
                        step.response.get(RESPONSE_HEADERS, header).set(true);
                    }
                } catch (RuntimeException | Error re) {
                    resultAction = ResultAction.ROLLBACK;
                    toThrow = re;
                } finally {
                    // See if executeStep put us in a state where we shouldn't do any more
                    if (toThrow != null || !canContinueProcessing()) {
                        // We're done.
                        executeResultHandlerPhase(toThrow);
                        exit = true; // we're on the return path
                    }
                }
                if (exit) {
                    return;
                }
            }
        } while (currentStage != Stage.DONE);

        assert primaryResponse != null; // else ModelControllerImpl executed an op with no steps

        // All steps ran and canContinueProcessing returned true for the last one, so...
        executeDoneStage(primaryResponse);
    }

    private CapabilityRegistry.RuntimeStatus getStepExecutionStatus(Step step) {
        if (booting || currentStage != Stage.RUNTIME || !RUNTIME_LIMITED_STATES.contains(processState.getState())
                || (step.operationDefinition != null && (step.operationDefinition.getFlags().contains(OperationEntry.Flag.READ_ONLY)
                        || step.operationDefinition.getFlags().contains(OperationEntry.Flag.RUNTIME_ONLY)))) {
            return RuntimeCapabilityRegistry.RuntimeStatus.NORMAL;
        }
        ImmutableManagementResourceRegistration mrr = step.getManagementResourceRegistration(getManagementModel());
        if (mrr != null) {
            if (mrr.isRuntimeOnly()) {
                return RuntimeCapabilityRegistry.RuntimeStatus.NORMAL;
            }
            String opName = step.operationDefinition != null ? step.operationDefinition.getName() : null;
            if ((WRITE_ATTRIBUTE_OPERATION.equals(opName) || UNDEFINE_ATTRIBUTE_OPERATION.equals(opName)) && step.operation.hasDefined(NAME)) {
                String attrName = step.operation.get(NAME).asString();
                AttributeAccess aa = mrr.getAttributeAccess(PathAddress.EMPTY_ADDRESS, attrName);
                if (aa != null && aa.getStorageType() == AttributeAccess.Storage.RUNTIME) {
                    return RuntimeCapabilityRegistry.RuntimeStatus.NORMAL;
                }
            }
        }
        return getStepCapabilityStatus(step);
    }

    private boolean tryStageCompleted(Stage currentStage) {
        boolean result;
        try {
            result = stageCompleted(currentStage);
        } catch (RuntimeException e) {
            result = false;
            if (!initialResponse.hasDefined(FAILURE_DESCRIPTION)) {
                initialResponse.get(FAILURE_DESCRIPTION).set(ControllerLogger.MGMT_OP_LOGGER.unexpectedOperationExecutionFailureDescription(e));
            }
            ControllerLogger.MGMT_OP_LOGGER.unexpectedOperationExecutionException(e, controllerOperations);
        }
        return result;
    }

    private void executeDoneStage(ModelNode primaryResponse) {

        // All steps are completed without triggering rollback;
        // time for final processing

        Throwable toThrow = null;
        try {
            // Prepare persistence of any configuration changes
            ConfigurationPersister.PersistenceResource persistenceResource = null;
            if (resultAction != ResultAction.ROLLBACK) {
                try {
                    persistenceResource = createPersistenceResource();
                } catch (ConfigurationPersistenceException e) {
                    MGMT_OP_LOGGER.failedToPersistConfigurationChange(e);
                    primaryResponse.get(OUTCOME).set(FAILED);
                    primaryResponse.get(FAILURE_DESCRIPTION).set(ControllerLogger.ROOT_LOGGER.failedToPersistConfigurationChange(e.getLocalizedMessage()));
                    resultAction = ResultAction.ROLLBACK;
                    executeResultHandlerPhase(null);
                    return;
                }
            }

            // Allow any containing TransactionControl to vote
            final AtomicReference<ResultAction> ref = new AtomicReference<ResultAction>(
                    transactionControl == null ? ResultAction.KEEP : ResultAction.ROLLBACK);
            if (transactionControl != null) {
                if (MGMT_OP_LOGGER.isTraceEnabled()) {
                    MGMT_OP_LOGGER.trace("Prepared response is " + activeStep.response);
                }
                transactionControl.operationPrepared(new ModelController.OperationTransaction() {
                    public void commit() {
                        ref.set(ResultAction.KEEP);
                    }

                    public void rollback() {
                        ref.set(ResultAction.ROLLBACK);
                    }
                }, primaryResponse, this);
            }
            resultAction = ref.get();

            // Commit the persistence of any configuration changes
            if (persistenceResource != null) {
                if (resultAction == ResultAction.ROLLBACK) {
                    persistenceResource.rollback();
                } else {
                    persistenceResource.commit();
                }
            }
        } catch (Throwable t) {
            toThrow = t;
            resultAction = ResultAction.ROLLBACK;
        } finally {
            try {
                executeResultHandlerPhase(toThrow);
            } finally {
                // For read-only ops, DoneStagePlaceholder can create cruft 'rolled-back' nodes. Remove them.
                if (primaryResponse.has(ROLLED_BACK) && !primaryResponse.hasDefined(ROLLED_BACK)) {
                    primaryResponse.remove(ROLLED_BACK);
                }
            }
        }
    }

    private void executeResultHandlerPhase(Throwable toThrow) {
        respectInterruption = false;
        try {
            logAuditRecord();
            emitNotifications();
        } finally {
            if (resultAction != ResultAction.KEEP) {
                operationRollingBack();
            }
            // Execute the result handlers
            activeStep.finalizeStep(toThrow);
        }
    }

    @Override
    public void emit(Notification notification) {
        // buffer the notification and eventual missing description warning now
        // but emit it and log the warning only when an operation is successful in emitNotifications()
        checkUndefinedNotification(notification);
        notifications.add(notification);

        if (modifiedResourcesForModelValidation != null) {
            //Hook for doing validation
            String type = notification.getType();
            switch (type) {
                case ATTRIBUTE_VALUE_WRITTEN_NOTIFICATION:
                case RESOURCE_ADDED_NOTIFICATION: {
                    PathAddress addr = notification.getSource();
                    synchronized (modifiedResourcesForModelValidation) {
                        if (!modifiedResourcesForModelValidation.contains(addr)) {
                            modifiedResourcesForModelValidation.add(addr);
                        }
                    }
                    break;
                }
                case RESOURCE_REMOVED_NOTIFICATION: {
                    PathAddress addr = notification.getSource();
                    synchronized (modifiedResourcesForModelValidation) {
                        if (modifiedResourcesForModelValidation.contains(addr)) {
                            modifiedResourcesForModelValidation.remove(addr);
                        }
                    }
                    break;
                }
                default:
                    //do nothing
            }
        }
    }


    private void emitNotifications() {
        // emit notifications and log missing descriptions warnings only if the action is kept
        if (resultAction != ResultAction.ROLLBACK) {
            synchronized (missingNotificationDescriptionWarnings) {
                for (String warning : missingNotificationDescriptionWarnings) {
                    ControllerLogger.ROOT_LOGGER.warn(warning);
                }
                missingNotificationDescriptionWarnings.clear();
            }
            synchronized (notifications) {
                if (notifications.isEmpty()) {
                    return;
                }
                notificationSupport.emit(notifications.toArray(new Notification[notifications.size()]));
                notifications.clear();
            }
        }
    }

    /**
     * Check that each emitted notification is properly described by its source.
     */
    private void checkUndefinedNotification(Notification notification) {
        String type = notification.getType();
        PathAddress source = notification.getSource();
        Map<String, NotificationEntry> descriptions = getRootResourceRegistration().getNotificationDescriptions(source, true);
        if (!descriptions.keySet().contains(type)) {
            missingNotificationDescriptionWarnings.add(ControllerLogger.ROOT_LOGGER.notificationIsNotDescribed(type, source));
        }
    }

    private void addBootFailureDescription() {
        if (isBootOperation() && activeStep != null && activeStep.response.hasDefined(FAILURE_DESCRIPTION)) {
            controller.addFailureDescription(activeStep.operation.clone(), activeStep.response.get(FAILURE_DESCRIPTION).clone());
        }
    }

    abstract boolean isBootOperation();

    private boolean canContinueProcessing() {

        // Cancellation is detected via interruption.
        if (Thread.currentThread().isInterrupted()) {
            cancelled = true;
        }
        // Rollback when any of:
        // 1. operation is cancelled
        // 2. operation failed in model phase
        // 3. operation failed in runtime/verify and rollback_on_fail is set
        // 2. operation failed in domain phase
        // 5. isRollbackOnly
        if (cancelled) {
            if (activeStep != null) {
                activeStep.response.get(OUTCOME).set(CANCELLED);
                activeStep.response.get(FAILURE_DESCRIPTION).set(ControllerLogger.ROOT_LOGGER.operationCancelled());
                activeStep.response.get(ROLLED_BACK).set(true);
                ControllerLogger.MGMT_OP_LOGGER.tracef("Rolling back on cancellation of operation %s on address %s in stage %s",
                        activeStep.operationId.name, activeStep.operationId.address, currentStage);
            }
            resultAction = ResultAction.ROLLBACK;
        } else if (activeStep != null && activeStep.hasFailed()
                && (isRollbackOnRuntimeFailure() || currentStage == Stage.MODEL || currentStage == Stage.DOMAIN)) {
            activeStep.response.get(OUTCOME).set(FAILED);
            activeStep.response.get(ROLLED_BACK).set(true);

            // runtime failure description needs to be attached to context to roll back in previous stage.
            if (isRollbackOnRuntimeFailure() && activeStep.response.hasDefined(FAILURE_DESCRIPTION)) {
                attach(ReadResourceHandler.ROLLBACKED_FAILURE_DESC, activeStep.response.get(FAILURE_DESCRIPTION));
            }

            if (getAttachment(ServiceVerificationHelper.DEFFERED_ROLLBACK_ATTACHMENT) == null) {
                resultAction = ResultAction.ROLLBACK;
            } else {
                detach(ServiceVerificationHelper.DEFFERED_ROLLBACK_ATTACHMENT);
            }
            ControllerLogger.MGMT_OP_LOGGER.tracef("Rolling back on failed response %s to operation %s on address %s in stage %s",
                    activeStep.response, activeStep.operationId.name, activeStep.operationId.address, currentStage);
        } else if (activeStep != null && activeStep.hasFailed()) {
            ControllerLogger.MGMT_OP_LOGGER.tracef("Not rolling back on failed response %s to operation %s on address %s in stage %s",
                    activeStep.response, activeStep.operationId.name, activeStep.operationId.address, currentStage);
        }
        return resultAction != ResultAction.ROLLBACK;
    }

    @SuppressWarnings("ConstantConditions")
    private void executeStep(final Step step) {

        if (this.activeStep != null) {
            // As the final activity for the current activeStep, have it link the next step to execute
            // into the chain of steps that get called in Stage.DONE.
            this.activeStep.linkNextStep(step);
        }
        this.activeStep = step;

        try {
            try {
                ClassLoader oldTccl = WildFlySecurityManager.setCurrentContextClassLoaderPrivileged(step.handler.getClass());
                try {
                    step.handler.execute(this, step.operation);
                    // AS7-6046
                    if (step.hasFailed()) {
                        logStepFailure(step, false);
                    }
                    if (step.serviceVerificationHelper != null) {
                        addStep(step.serviceVerificationHelper, Stage.VERIFY);
                    }
                    checkDeprecatedOperation(step);
                } finally {
                    step.executed = true;
                    WildFlySecurityManager.setCurrentContextClassLoaderPrivileged(oldTccl);
                }

            } catch (Throwable t) {
                // If it doesn't implement OperationClientException marker interface, throw it on to outer catch block
                if (!(t instanceof OperationClientException)) {
                    throw t;
                }
                // Handler threw OCE; that's equivalent to a request that we set the failure description
                final ModelNode failDesc = OperationClientException.class.cast(t).getFailureDescription();
                step.response.get(FAILURE_DESCRIPTION).set(failDesc);

                logStepFailure(step, true);
            }
        } catch (Throwable t) {
            // Handling for throwables that don't implement OperationClientException marker interface
            if (!step.requiresDoneStage && !isExternalClient()) {
                logStepFailure(step, false);
            } else {
                MGMT_OP_LOGGER.operationFailed(t, step.operation.get(OP), step.operation.get(OP_ADDR), currentStage);
            }

            // Provide a failure description if there isn't one already
            if (!step.hasFailed()) {
                step.response.get(FAILURE_DESCRIPTION).set(ControllerLogger.ROOT_LOGGER.operationHandlerFailed(t.toString()));
            }
            step.response.get(OUTCOME).set(FAILED);
            resultAction = getFailedResultAction(t);
            if (resultAction == ResultAction.ROLLBACK) {
                step.response.get(ROLLED_BACK).set(true);
            }
        } finally {
            addBootFailureDescription();
        }
    }

    private void checkDeprecatedOperation(Step step) {
        if (currentStage == Stage.MODEL // any user-specified op will have a Stage.MODEL step, so no need to check other stages
                && step.operationDefinition != null
                // Ignore cases where the step's definition is the same as it's parent, as we don't
                // want to repeatedly log warnings from internal child steps added by a parent
                && (step.usesOwnDefinition)) {
            DeprecationData deprecationData = step.operationDefinition.getDeprecationData();
            if (deprecationData != null && deprecationData.isNotificationUseful()) {
                String deprecatedMsg = ControllerLogger.DEPRECATED_LOGGER.operationDeprecatedMessage(step.operationDefinition.getName(),
                        step.address.toCLIStyleString());
                addResponseWarning(Level.WARNING, new ModelNode(deprecatedMsg));
            }
        }
    }

    void addModifiedResourcesForModelValidation(Set<PathAddress> modifiedResources) {
        if (modifiedResourcesForModelValidation != null) {
            synchronized (modifiedResourcesForModelValidation) {
                modifiedResourcesForModelValidation.addAll(modifiedResources);
            }
        }
    }

    private void logStepFailure(Step step, boolean fromOCE) {

        // Use ERROR for any boot failure or for any failure that may affect this processes' runtime services.
        // Stage.RUNTIME failures in read-only steps haven't affected runtime services so don't require ERROR
        // logging if they came from an internal caller that presumably will handle a failure response without
        // the aid of the log.
        // Post-boot MODEL failures aren't ERROR logged as they have no impact outside the scope of
        // the soon-to-be-abandoned OperationContext.
        if (isBooting() || currentStage == Stage.VERIFY
                || (currentStage == Stage.RUNTIME && (step.requiresDoneStage || isExternalClient()))) {
            MGMT_OP_LOGGER.operationFailed(step.operation.get(OP), step.operation.get(OP_ADDR), currentStage,
                    step.response.get(FAILURE_DESCRIPTION));
        } else if (fromOCE) {
            MGMT_OP_LOGGER.operationFailedOnClientError(step.operation.get(OP), step.operation.get(OP_ADDR), currentStage,
                    step.response.get(FAILURE_DESCRIPTION));
        } else if (currentStage != Stage.DOMAIN) {
            // Post-boot Stage.RUNTIME issues with internal read-only calls
            // TODO consider ERROR or DEBUG logging Stage.DOMAIN problems if it's clear the message will be comprehensible.
            // Currently Stage.DOMAIN failure handling involves message manipulation before sending the
            // failure data to the client; logging stuff before that is done is liable to just produce a log mess.
            MGMT_OP_LOGGER.operationFailed(step.operation.get(OP), step.operation.get(OP_ADDR), currentStage,
                        step.response.get(FAILURE_DESCRIPTION), "");
        }
    }

    private boolean isExternalClient() {
        AccessMechanism am = operationHeaders.getAccessMechanism();
        return am != null && am != AccessMechanism.IN_VM_USER;
    }

    private void handleContainerStabilityFailure(ModelNode response, Exception cause) {
        boolean interrupted = cause instanceof InterruptedException;
        assert interrupted || cause instanceof TimeoutException;

        if (response != null) {
            response.get(OUTCOME).set(CANCELLED);
            response.get(FAILURE_DESCRIPTION).set(interrupted ? ControllerLogger.ROOT_LOGGER.operationCancelled(): ControllerLogger.ROOT_LOGGER.timeoutExecutingOperation());
            response.get(ROLLED_BACK).set(true);
        }
        resultAction = ResultAction.ROLLBACK;
        respectInterruption = false;
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void throwThrowable(Throwable toThrow) {
        if (toThrow != null) {
            if (toThrow instanceof RuntimeException) {
                throw (RuntimeException) toThrow;
            } else {
                throw (Error) toThrow;
            }
        }
    }

    /**
     * Decide whether failure should trigger a rollback.
     *
     * @param cause
     *            the cause of the failure, or {@code null} if failure is not
     *            the result of catching a throwable
     * @return the result action
     */
    private ResultAction getFailedResultAction(Throwable cause) {
        if (currentStage == Stage.MODEL || cancelled || isRollbackOnRuntimeFailure() || isRollbackOnly()
                || (cause != null && !(cause instanceof OperationFailedException))) {
            return ResultAction.ROLLBACK;
        }
        return ResultAction.KEEP;
    }

    public final ProcessType getProcessType() {
        return processType;
    }

    public final RunningMode getRunningMode() {
        return runningMode;
    }

    @Override
    public final boolean isNormalServer() {
        return processType.isServer() && runningMode == RunningMode.NORMAL;
    }

    @Override
    public final boolean isRollbackOnly() {
        return resultAction == ResultAction.ROLLBACK;
    }

    @Override
    public final void setRollbackOnly() {
        resultAction = ResultAction.ROLLBACK;
    }

    final boolean isRollingBack() {
        return currentStage == Stage.DONE && resultAction == ResultAction.ROLLBACK;
    }

    @Override
    public final void reloadRequired() {
        if (processState.isReloadSupported()) {
            if(isBooting()) {
                MGMT_OP_LOGGER.debug("Server is booting so we didn't set the reload required flag");
                return;
            }
            activeStep.setRestartStamp(processState.setReloadRequired());
            activeStep.response.get(RESPONSE_HEADERS, OPERATION_REQUIRES_RELOAD).set(true);
            getManagementModel().getCapabilityRegistry().capabilityReloadRequired(activeStep.address,
                    activeStep.getManagementResourceRegistration(getManagementModel()));
        } else {
            restartRequired();
        }
    }

    @Override
    public final void restartRequired() {
        if (isBooting()) {
            MGMT_OP_LOGGER.debug("Server is booting so we didn't set the restart required flag");
            return;
        }
        activeStep.setRestartStamp(processState.setRestartRequired());
        activeStep.response.get(RESPONSE_HEADERS, OPERATION_REQUIRES_RESTART).set(true);
        getManagementModel().getCapabilityRegistry().capabilityRestartRequired(activeStep.address,
                activeStep.getManagementResourceRegistration(getManagementModel()));
    }

    @Override
    public final void revertReloadRequired() {
        if (processState.isReloadSupported()) {
            //skip if reloadRequired() was  not called
            if (this.activeStep.restartStamp == null) {
                return;
            }
            processState.revertReloadRequired(this.activeStep.restartStamp);
            if (activeStep.response.get(RESPONSE_HEADERS).hasDefined(OPERATION_REQUIRES_RELOAD)) {
                activeStep.response.get(RESPONSE_HEADERS).remove(OPERATION_REQUIRES_RELOAD);
                if (activeStep.response.get(RESPONSE_HEADERS).asInt() == 0) {
                    activeStep.response.remove(RESPONSE_HEADERS);
                }
            }
        } else {
            revertRestartRequired();
        }
    }

    @Override
    public final void revertRestartRequired() {
        //skip if restartRequired() was  not called
        if (this.activeStep.restartStamp == null) {
            return;
        }
        processState.revertRestartRequired(this.activeStep.restartStamp);
        if (activeStep.response.get(RESPONSE_HEADERS).hasDefined(OPERATION_REQUIRES_RESTART)) {
            activeStep.response.get(RESPONSE_HEADERS).remove(OPERATION_REQUIRES_RESTART);
            if (activeStep.response.get(RESPONSE_HEADERS).asInt() == 0) {
                activeStep.response.remove(RESPONSE_HEADERS);
            }
        }
    }

    @Override
    public final void runtimeUpdateSkipped() {
        activeStep.response.get(RESPONSE_HEADERS, RUNTIME_UPDATE_SKIPPED).set(true);
    }

    @Override
    public final ModelNode getResult() {
        return activeStep.response.get(RESULT);
    }

    @Override
    public final boolean hasResult() {
        return activeStep.response.has(RESULT);
    }

    @Override
    public String attachResultStream(String mimeType, InputStream stream) {
        String domainUUID = UUID.randomUUID().toString();
        attachResultStream(domainUUID, mimeType, stream);
        return domainUUID;
    }

    @Override
    public synchronized void attachResultStream(String uuid, String mimeType, InputStream stream) {
        if (responseStreams == null) {
            responseStreams = new LinkedHashMap<>();
        }
        responseStreams.put(uuid, new OperationStreamEntry(uuid, mimeType, stream));
    }

    /**
     * Get the streams attached to this context. The returned map's iterators will return items in
     * the order in which the streams were attached to the context.
     *
     * @return the streams, or {@code null} if none were attached.
     */
    Map<String, OperationResponse.StreamEntry> getResponseStreams() {
        return responseStreams;
    }

    @Override
    public final ModelNode getServerResults() {
        if (! processType.isHostController()) {
            throw ControllerLogger.ROOT_LOGGER.serverResultsAccessNotAllowed(ProcessType.HOST_CONTROLLER, processType);
        }
        return activeStep.response.get(SERVER_GROUPS);
    }

    private boolean hasMoreSteps() {
        Stage stage = currentStage;
        boolean more = !steps.get(stage).isEmpty();
        while (!more && stage.hasNext()) {
            stage = stage.next();
            more = !steps.get(stage).isEmpty();
        }
        return more;
    }

    @Override
    public SecurityIdentity getSecurityIdentity() {
        // We don't cache the result as the identity could be switched mid-call.
        return securityIdentitySupplier.get();
    }

    @Override
    public Environment getCallEnvironment() {
        return callEnvironment;
    }


    @Override
    public boolean isDefaultRequiresRuntime() {
        if (getProcessType().isServer()) {
            return isNormalServer();
        } else if (getProcessType().isHostController()) {
            return isHostCapableAddress();
        }
        return false;
    }

    private boolean isHostCapableAddress() {
        if (activeStep.address.size() >= 2 && activeStep.address.getElement(0).getKey().equals(HOST) && activeStep.address.getElement(1).getKey().equals(SUBSYSTEM)) {
            return true;
        }
        return false;
    }

    private boolean addModelValidationSteps() {
        if (modifiedResourcesForModelValidation == null) {
            return false;
        }
        synchronized (modifiedResourcesForModelValidation) {
            if (modifiedResourcesForModelValidation.isEmpty()) {
                return false;
            }
            ModelNode op = Util.createOperation(ValidateModelStepHandler.INTERNAL_MODEL_VALIDATION_NAME, PathAddress.EMPTY_ADDRESS);
            addStep(op, new ValidateModelStepHandler(getManagementModel(), modifiedResourcesForModelValidation,
                    extraValidationStepHandler), Stage.MODEL);
            modifiedResourcesForModelValidation.clear();
        }
        return true;
    }

    abstract class AbstractStep {
        AbstractStep predecessor;
        abstract void finalizeStep();
        abstract boolean matches(Step step);
        final void finalizeRollbackResponse(ModelNode outcome, ModelNode rolledback) {
            outcome.set(cancelled ? CANCELLED : FAILED);
            rolledback.set(true);
        }
        final void finalizeNonRollbackResponse(ModelNode outcome, ModelNode rolledback) {
            // Getting a rolledBack node means we failed; otherwise we succeeded
            outcome.set(rolledback != null ? FAILED : SUCCESS);
            if (rolledback != null) {
                // We didn't roll back despite failure. Report this
                rolledback.set(false);
            }
        }
    }

    class Step extends AbstractStep {
        private final boolean usesOwnDefinition;
        private final OperationDefinition operationDefinition;
        private final OperationStepHandler handler;
        private final Stage forStage = AbstractOperationContext.this.currentStage;
        final ModelNode response;
        final ModelNode operation;
        final PathAddress address;
        final OperationId operationId;
        private Object restartStamp;
        private ResultHandler resultHandler;
        ServiceTarget serviceTarget;
        private ServiceVerificationHelper serviceVerificationHelper;
        private Set<ServiceController<?>> addedServices;
        boolean hasRemovals;
        boolean executed;
        // Whether standard Stage.DONE handling is needed, as opposed to lighter-weight handling.
        private boolean requiresDoneStage;
        ManagementResourceRegistration resourceRegistration;
        private DoneStagePlaceholder placeholder;

        private Step(OperationDefinition operationDefinition, final OperationStepHandler handler, final ModelNode response, final ModelNode operation,
                     final PathAddress address) {
            this.operationDefinition = operationDefinition != null ? operationDefinition : (activeStep != null ? activeStep.operationDefinition : null);
            this.usesOwnDefinition = activeStep == null || this.operationDefinition != activeStep.operationDefinition;
            this.handler = handler;
            this.response = response;
            this.operation = operation;
            this.address = address;
            String opName = operation.hasDefined(OP) ? operation.require(OP).asString() : null;
            this.operationId = new OperationId(this.address, opName);
            // Create the outcome node early so it appears at the top of the
            // response
            response.get(OUTCOME);
            // Initialize a default no-op result handler. This will get used in two cases:
            // 1) execute completes normally, and OSH just didn't register a handler, meaning default behavior was wanted
            // 2) execute throws an exception. Here we just want a handler to avoid NPEs later, but we don't want it to do anything
            this.resultHandler = ResultHandler.NOOP_RESULT_HANDLER;
            // We assume a READ_ONLY step does not require any handling in Stage.DONE, unless it is in Stage.DOMAIN
            // Stage.DOMAIN is complex
            this.requiresDoneStage = forStage == Stage.DOMAIN
                    || this.operationDefinition == null
                    || !this.operationDefinition.getFlags().contains(OperationEntry.Flag.READ_ONLY);
        }

        boolean matches(Step other) {
            return this == other;
        }

        Step recordWriteLock() {
            recordModification("call to recordWriteLock()");
            return this;
        }

        Step modifyServiceContainer() {
            recordModification("ServiceContainer modification");
            return this;
        }

        /**
         * Gets a {@code ServiceTarget} {@link org.jboss.msc.service.ServiceTarget#subTarget() scoped to this step},
         * with a {@link org.jboss.as.controller.ServiceVerificationHelper} registered, so all services
         * created by the target will be monitored.
         * @param parent the parent target. Cannot be {@code null}
         * @return the service target. Will not be {@code null}
         */
        ServiceTarget getScopedServiceTarget(ServiceTarget parent) {
            if (serviceTarget == null) {
                serviceTarget = parent.subTarget();
                serviceTarget.addMonitor(getServiceVerificationHelper().getMonitor());
                recordModification("call to getServiceTarget() or getCapabilityServiceTarget()");
            }
            return serviceTarget;
        }

        /**
         * Tracks a service for possible removal on rollback.
         *
         * @param controller the service
         */
        void serviceAdded(ServiceController<?> controller) {
            if (!executed) {
                getAddedServices().add(controller);
                recordModification("call to ServiceBuilder.install()");
            } // else this is rollback stuff we ignore
        }

        /**
         * Tracks a service whose mode is changing for subsequent verification of service stability.
         * @param service the service
         */
        void serviceModeChanged(ServiceController<?> service) {
            // This should not be used for removals
            assert service.getMode() != ServiceController.Mode.REMOVE;

            if (!executed) {
                if (addedServices == null || !addedServices.contains(service)) {
                    getServiceVerificationHelper().getMonitor().addController(service);
                } // else we already handled this when it was added
                recordModification("call to ServiceController.setMode(...) or compareAndSetMode(...)");
            } // else this is rollback stuff we ignore
        }

        void hasRemovals() {
            hasRemovals = true;
            recordModification("call to removeService(...)");
        }

        ManagementResourceRegistration getManagementResourceRegistration(ManagementModel managementModel) {
            if (resourceRegistration == null) {
                resourceRegistration = managementModel.getRootResourceRegistration().getSubModel(address);
            }
            return resourceRegistration;
        }

        private ServiceVerificationHelper getServiceVerificationHelper() {
            if (serviceVerificationHelper == null) {
                serviceVerificationHelper = new ServiceVerificationHelper();
            }
            return serviceVerificationHelper;
        }

        private Set<ServiceController<?>> getAddedServices() {
            if (addedServices == null) {
                addedServices = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            return addedServices;
        }

        private boolean hasFailed() {
            return this.response.hasDefined(FAILURE_DESCRIPTION);
        }

        private void setRestartStamp(Object stamp) {
            this.restartStamp = stamp;
            recordModification("call to setReloadRequired() or setRestartRequired()");
        }

        private void setResultHandler(ResultHandler resultHandler) {
            this.resultHandler = resultHandler;
            this.requiresDoneStage = requiresDoneStage || resultHandler != ResultHandler.NOOP_RESULT_HANDLER;
        }

        private void recordModification(String reason) {
            boolean was = requiresDoneStage;
            requiresDoneStage = true;
            if (!was) {
                // TODO this situation indicates incorrect code. At some point make this a WARN.
                MGMT_OP_LOGGER.debugf("The handler %s for supposedly read-only operation %s has modified model or runtime state. " +
                        "Requested modification was: %s", handler, operationId.name, reason);
            }

        }

        /**
         * Link the next step that is about to become the active step into the chain of steps
         * that execute in Stage.DONE. Called once initial stage (i.e. not Stage.DONE) handling
         * of this step is complete and {@code nextStep} is about to become the active step.
        */
        private void linkNextStep(Step nextStep) {
            // A step's predecessor is this one, unless this step isn't needed hereafter for anything
            // other than recording the operation outcome, in which case the predecessor is a light-weight
            // placeholder based on this step. If a step isn't needed further, not including itself in the
            // chain of steps allows it to be gc'd and we can reduce the peak memory needed for operations
            // with a large number of steps.
            //
            // If we're the first step (no predecessor), assume we always needed hereafter.
            // (Perhaps this isn't true but there's little harm in assuming.)
            nextStep.predecessor = (requiresDoneStage || predecessor == null) ? this : new DoneStagePlaceholder(this);
        }

        /**
         * Perform any rollback needed to reverse this step (if this context is
         * rolling back), and release any locks taken by this step.
         *
         * @param toThrow
         *            RuntimeException or Error to throw when done; may be
         *            {@code null}
         */
        private void finalizeStep(Throwable toThrow) {
            try {
                finalizeStep();
            } catch (RuntimeException | Error t) {
                if (toThrow == null) {
                    toThrow = t;
                }
            }

            AbstractStep step = this.predecessor;
            while (step != null) {
                try {
                    step.finalizeStep();
                } catch (RuntimeException | Error t) {
                    if (toThrow == null) {
                        toThrow = t;
                    }
                }
                step = step.predecessor;
            }

            throwThrowable(toThrow);
        }

        void finalizeStep()  {

            AbstractOperationContext.this.activeStep = this;

            try {
                handleResult();

                if (currentStage != null && currentStage != Stage.DONE) {
                    currentStage = Stage.DONE;
                    response.get(OUTCOME).set(cancelled ? CANCELLED : FAILED);
                    response.get(ROLLED_BACK).set(true);
                    resultAction = getFailedResultAction(null);
                } else if (resultAction == ResultAction.ROLLBACK) {
                    finalizeRollbackResponse(response.get(OUTCOME), response.get(ROLLED_BACK));
                } else {
                    ModelNode rolledBackNode = hasFailed() ? response.get(ROLLED_BACK) : null;
                    finalizeNonRollbackResponse(response.get(OUTCOME), rolledBackNode);
                }

                if (ControllerLogger.MGMT_OP_LOGGER.isTraceEnabled()
                        && (forStage == Stage.MODEL || forStage == Stage.DOMAIN)) {
                    ControllerLogger.MGMT_OP_LOGGER.tracef("Final response for step handler %s handling %s in address %s is %s",
                            handler, operationId.name, operationId.address, response);
                }

            } finally {
                releaseStepLocks(this);

                if (predecessor == null) {
                    // We're returning from the outermost completeStep()
                    // Null out the current stage to disallow further access to
                    // the context
                    currentStage = null;
                }
            }
        }

        private void handleResult() {
            hasRemovals = false;
            try {
                try {
                    rollbackAddedServices();
                } finally {
                    try {
                        invokeResultHandler();
                    } finally {
                        if (hasRemovals) {
                            waitForRemovals();
                        }
                    }
                }
            } catch (Exception e) {
                final String failedRollbackMessage =
                        MGMT_OP_LOGGER.stepHandlerFailed(handler, operation.get(OP).asString(), address, e);
                MGMT_OP_LOGGER.errorf(e, failedRollbackMessage);
                report(MessageSeverity.ERROR, failedRollbackMessage);
            }
        }

        private void invokeResultHandler() {
            ClassLoader oldTccl = WildFlySecurityManager.setCurrentContextClassLoaderPrivileged(handler.getClass());
            try {
                resultHandler.handleResult(resultAction, AbstractOperationContext.this, operation);
            } finally {
                WildFlySecurityManager.setCurrentContextClassLoaderPrivileged(oldTccl);
            }
        }

        private void rollbackAddedServices() {
            if (resultAction == ResultAction.ROLLBACK && addedServices != null) {
                for (ServiceController<?> serviceController : addedServices) {
                    removeService(serviceController);
                }
            }
        }
    }

    /** Lightweight AbstractStep that only aims to populate the response's OUTCOME and ROLLED_BACK nodes in Stage.DONE */
    private class DoneStagePlaceholder extends AbstractStep {

        private final ModelNode outcome;
        private final ModelNode rolledBack;
        private final boolean failed;

        private DoneStagePlaceholder(Step replaces) {
            this.predecessor = replaces.predecessor;
            // Cache the OUTCOME and ROLLED_BACK nodes and the failed status
            // in order to populate the nodes in finalizeStep.
            // We don't need any of the other response data.
            this.outcome = replaces.response.get(OUTCOME);
            this.rolledBack = replaces.response.get(ROLLED_BACK);
            this.failed = replaces.hasFailed();
            replaces.placeholder = this;
        }

        @Override
        void finalizeStep() {
            // Differences from the implementation in Step
            // 1) We replaced a step with a no-op result handler, so we don't need to invoke it.
            // 2) We'll never be the first step finalized, so we don't need to take actions like
            //    changing the Stage to DONE or setting a result action
            // 3) We skip some trace logging that would require retaining a lot of state.
            try {
                if (resultAction == ResultAction.ROLLBACK) {
                    finalizeRollbackResponse(outcome, rolledBack);
                } else {
                    finalizeNonRollbackResponse(outcome, failed ? rolledBack : null);
                }
            } finally {
                // It shouldn't be possible that the Step that created us is holding locks,
                // as Step.linkNextStep wouldn't have created us if it was used for writes.
                // But to be safe, give the context a chance to release locks.
                releaseStepLocks(this);

                if (predecessor == null) {
                    // We're returning from the outermost completeStep()
                    // Null out the current stage to disallow further access to
                    // the context
                    currentStage = null;
                }
            }
        }

        @Override
        boolean matches(Step step) {
            return step != null && this == step.placeholder;
        }
    }

    private static class RollbackDelegatingResultHandler implements ResultHandler {

        private final RollbackHandler delegate;

        private RollbackDelegatingResultHandler(RollbackHandler delegate) {
            this.delegate = delegate;
        }


        @Override
        public void handleResult(ResultAction resultAction, OperationContext context, ModelNode operation) {
            if (resultAction == ResultAction.ROLLBACK) {
                delegate.handleRollback(context, operation);
            }
        }
    }

    static class OperationId {
        final PathAddress address;
        final String name;

        OperationId(ModelNode operation) {
            this(PathAddress.pathAddress(operation.get(OP_ADDR)), operation.hasDefined(OP) ? operation.get(OP).asString() : null);
        }

        private OperationId(PathAddress address, String name) {
            this.address = address;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            OperationId that = (OperationId) o;

            return address.equals(that.address) && !(name != null ? !name.equals(that.name) : that.name != null);

        }

        @Override
        public int hashCode() {
            int result = address.hashCode();
            result = 31 * result + (name != null ? name.hashCode() : 0);
            return result;
        }
    }

    private static class OperationStreamEntry implements OperationResponse.StreamEntry {
        private final InputStream stream;
        private final String mimeType;
        private final String uuid;

        private OperationStreamEntry(final String uuid, String mimeType, InputStream stream) {
            this.uuid = uuid;
            this.mimeType = mimeType;
            this.stream = stream;
        }

        @Override
        public String getUUID() {
            return uuid;
        }

        @Override
        public String getMimeType() {
            return mimeType;
        }

        @Override
        public InputStream getStream() {
            return stream;
        }

        @Override
        public void close() throws IOException {
            StreamUtils.safeClose(stream);
        }
    }
}
