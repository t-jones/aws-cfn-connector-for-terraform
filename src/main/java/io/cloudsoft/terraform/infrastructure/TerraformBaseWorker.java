package io.cloudsoft.terraform.infrastructure;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;

import javax.annotation.Nullable;

import com.google.common.base.Preconditions;

import io.cloudsoft.terraform.infrastructure.commands.RemoteDetachedTerraformProcess;
import io.cloudsoft.terraform.infrastructure.commands.RemoteDetachedTerraformProcess.TerraformCommand;
import io.cloudsoft.terraform.infrastructure.commands.RemoteDetachedTerraformProcessNohup;
import io.cloudsoft.terraform.infrastructure.commands.RemoteDetachedTerraformProcessSystemd;
import io.cloudsoft.terraform.infrastructure.commands.RemoteTerraformProcess;
import lombok.Getter;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;

public abstract class TerraformBaseWorker<Steps extends Enum<Steps>> {

    // Mirror Terraform, which maxes its state checks at 10 seconds when working on long jobs
    private static final int MAX_CHECK_INTERVAL_SECONDS = 10;
    // Use YAML doc separator to separate logged messages
    public static final CharSequence LOG_MESSAGE_SEPARATOR = "---";
    public static final String MAIN_LOG_BUCKET_FILE = "cfn-log.txt";

    @Getter
    protected AmazonWebServicesClientProxy proxy;
    @Getter
    protected ResourceHandlerRequest<ResourceModel> request;
    @Getter
    protected ResourceModel model;
    @Getter
    protected CallbackContext callbackContext;
    @Getter
    private Logger logger;
    @Getter
    private final Class<Steps> stepsEnumClass;
    
    private TerraformParameters parameters;

    protected Steps currentStep;

    // === init and accessors ========================

    public TerraformBaseWorker(Class<Steps> stepsEnumClass) {
        this.stepsEnumClass = stepsEnumClass;
    }
    
    protected void init(
            @Nullable AmazonWebServicesClientProxy proxy,
            ResourceHandlerRequest<ResourceModel> request,
            @Nullable CallbackContext callbackContext,
            Logger logger) {
        if (this.request!=null) {
            throw new IllegalStateException("Handler can only be setup and used once, and request has already been initialized when attempting to re-initialize it");
        }
        this.proxy = proxy;
        this.request = Preconditions.checkNotNull(request, "request");
        this.model = request.getDesiredResourceState();
        this.callbackContext = callbackContext!=null ? callbackContext : new CallbackContext();
        this.logger = Preconditions.checkNotNull(logger, "logger");
    }

    public synchronized TerraformParameters getParameters() {
        if (parameters==null) {
            if (proxy==null) {
                throw new IllegalStateException("Parameters cannot be accessed before proxy set during init");
            }
            parameters = new TerraformParameters(proxy);
        }
        return parameters;
    }

    // for testing
    public synchronized void setParameters(TerraformParameters parameters) {
        if (this.parameters!=null) {
            throw new IllegalStateException("Handler can only be setup and used once, and parameters have already initialized when attempting to re-initializee them");
        }
        this.parameters = Preconditions.checkNotNull(parameters, "parameters");
    }

    // === lifecycle ========================

    public ProgressEvent<ResourceModel, CallbackContext> runHandlingError() {
        try {
            log(getClass().getName() + " lambda starting, model: "+model+", callback: "+callbackContext);
            preRunStep();
            ProgressEvent<ResourceModel, CallbackContext> result = runStep();
            log(getClass().getName() + " lambda exiting, status: "+result.getStatus()+", callback: "+result.getCallbackContext()+", message: "+result.getMessage());
            if (OperationStatus.SUCCESS==result.getStatus()) {
                logUserLogOnly("SUCCESS: "+model);
            }
            return result;

        } catch (ConnectorHandlerFailures.Handled e) {
            log(getClass().getName() + " lambda exiting with error");
            String message = "FAILING: "+e.getMessage();
            logUserLogOnly(message);
            return statusFailed(message);

        } catch (ConnectorHandlerFailures.Unhandled e) {
            if (e.getCause()!=null) {
                logExceptionIncludingUserLog("FAILING: "+e.getMessage(), e.getCause());
            } else {
                logIncludingUserLog("FAILING: "+e.getMessage());
            }
            log(getClass().getName() + " lambda exiting with error");
            return statusFailed(e.getMessage());

        } catch (Exception e) {
            logExceptionIncludingUserLog("FAILING: "+e, e);
            log(getClass().getName() + " lambda exiting with error");
            return statusFailed((currentStep!=null ? currentStep+": " : "")+e);
        }
    }

    protected void preRunStep() {
        if (getCallbackContext().stepId == null) {
            if (stepsEnumClass.getEnumConstants().length==0) {
                // leave it null
            } else {
                currentStep = stepsEnumClass.getEnumConstants()[0];
            }
            getCallbackContext().commandRequestId = Configuration.getIdentifier(true,  6);
            log("Using "+getCallbackContext().commandRequestId+" to uniquely identify this command across all steps (stack element "+model.getIdentifier()+", request "+request.getClientRequestToken()+")");
            uploadCompleteLog(MAIN_LOG_BUCKET_FILE, "Beginning command requested "+getClass().getSimpleName()+" on "+model.getIdentifier()+", command "+getCallbackContext().commandRequestId);
            
        } else {
            // continuing a step
            currentStep = Enum.valueOf(stepsEnumClass, callbackContext.stepId);
        }
        
        if (callbackContext.logBucketName==null && model.getLogBucketUrl()!=null) {
            setCallbackLogBucketNameFromModelUrl();
        } else if (callbackContext.logBucketName!=null && model.getLogBucketUrl()==null) {
            // during creation, this isn't remembered in the model, so make sure we persist it
            setModelLogBucketUrlFromCallbackContextName();
        }
    }

    protected abstract ProgressEvent<ResourceModel, CallbackContext> runStep() throws IOException;

    // === utils ========================
    
    protected void setCallbackLogBucketNameFromModelUrl() {
        callbackContext.logBucketName = model.getLogBucketUrl().substring(model.getLogBucketUrl().lastIndexOf("/")+1);
    }
    
    protected void setModelLogBucketUrlFromCallbackContextName() {
        model.setLogBucketUrl(
            callbackContext.logBucketName==null ? null :
                "https://s3.console.aws.amazon.com/s3/buckets/"+callbackContext.logBucketName);
    }

    protected void log(String message) {
        System.out.println(message);
        System.out.println(LOG_MESSAGE_SEPARATOR);
        if (logger!=null) {
            logger.log(message);
        }
    }

    protected void logIncludingUserLog(String message) {
        log(message);
        logUserLogOnly(message);
    }

    private void logUserLogOnly(String message) {
        uploadCompleteLog(MAIN_LOG_BUCKET_FILE, downloadLog(MAIN_LOG_BUCKET_FILE).orElse("")+message+"\n");
    }
    
    protected final void logException(String message, Throwable e) {
        log(message + "\n" + getStackTraceAsString(e));
    }

    protected final void logExceptionIncludingUserLog(String message, Throwable e) {
        logIncludingUserLog(message + "\n" + getStackTraceAsString(e));
    }

    protected String getStackTraceAsString(Throwable e) {
        final StringWriter sw = new StringWriter();
        final PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    protected ProgressEvent<ResourceModel, CallbackContext> statusFailed(String message) {
        return ProgressEvent.<ResourceModel, CallbackContext>builder()
                .resourceModel(model)
                .status(OperationStatus.FAILED)
                .message(message)
                .build();
    }

    protected ProgressEvent<ResourceModel, CallbackContext> statusSuccess() {
        return ProgressEvent.<ResourceModel, CallbackContext>builder()
                .resourceModel(model)
                .status(OperationStatus.SUCCESS)
                .build();
    }

    protected ProgressEvent<ResourceModel, CallbackContext> statusInProgress() {
        return ProgressEvent.<ResourceModel, CallbackContext>builder()
                .resourceModel(model)
                .callbackContext(callbackContext)
                .callbackDelaySeconds(nextDelay(callbackContext))
                .status(OperationStatus.IN_PROGRESS)
                .message(currentStep == null ? null : "Step: " + currentStep)
                .build();
    }

    int nextDelay(CallbackContext callbackContext) {
        if (callbackContext.lastDelaySeconds < 0) {
            callbackContext.lastDelaySeconds = 0;
        } else if (callbackContext.lastDelaySeconds == 0) {
            callbackContext.lastDelaySeconds = 1;
        } else if (callbackContext.lastDelaySeconds < MAX_CHECK_INTERVAL_SECONDS) {
            // exponential backoff
            callbackContext.lastDelaySeconds =
                    Math.min(MAX_CHECK_INTERVAL_SECONDS, 2 * callbackContext.lastDelaySeconds);
        }
        return callbackContext.lastDelaySeconds;
    }

    protected final void advanceTo(Steps nextStep) {
        log("Entering step "+nextStep);
        callbackContext.stepId = nextStep.toString();
        callbackContext.lastDelaySeconds = -1;
    }

    protected final RemoteDetachedTerraformProcess remoteProcessForCommand(TerraformCommand command) {
        String processManager = callbackContext.processManager;
        if (processManager==null) {
            processManager = getParameters().getProcessManager();
        }
        
        // ensure it doesn't change in the middle of a run, even if parameters are changed
        callbackContext.processManager = processManager; 
        if ("systemd".equals(processManager)) {
            return RemoteDetachedTerraformProcessSystemd.of(this, command);
            
        } else if ("nohup".equals(processManager)) {
            return RemoteDetachedTerraformProcessNohup.of(this, command);
            
        } else {
            throw new IllegalStateException("Unsupported process manager type");
        }
    }
    
    protected final RemoteTerraformProcess remoteTerraformProcess() {
        return RemoteTerraformProcess.of(this);
    }

    protected RemoteDetachedTerraformProcess tfInit() {
        return remoteProcessForCommand(RemoteDetachedTerraformProcess.TerraformCommand.TF_INIT);
    }

    protected RemoteDetachedTerraformProcess tfApply() {
        return remoteProcessForCommand(RemoteDetachedTerraformProcess.TerraformCommand.TF_APPLY);
    }

    protected RemoteDetachedTerraformProcess tfDestroy() {
        return remoteProcessForCommand(RemoteDetachedTerraformProcess.TerraformCommand.TF_DESTROY);
    }

    private void drainPendingRemoteLogs(RemoteDetachedTerraformProcess process) throws IOException {
        String str;
        str = process.getIncrementalStdout();
        if (!str.isEmpty())
            log("New standard output data:\n" + str);
        str = process.getIncrementalStderr();
        if (!str.isEmpty())
            log("New standard error data:\n" + str);
    }

    protected boolean checkStillRunningOrError(RemoteDetachedTerraformProcess process) throws IOException {
        // Always drain pending log messages regardless of any other activity/conditions.
        // That said, do not drain _before_ establishing whether the remote process is still
        // running as that would be a race against short-lived processes and would require a
        // second drain in case the process has finished and would result in a short Terraform
        // log split across two CloudWatch messages for no obvious reason.
        final boolean isRunning = process.isRunning();
        drainPendingRemoteLogs(process);
        if (isRunning) {
            return true;
        }

        final String stdout = process.getFullStdout();
        final String stderr = process.getFullStderr();

        final String s3BucketName = callbackContext.getLogBucketName();
        if (s3BucketName != null) {
            uploadCompleteLog(process.getCommandName()+"-"+"stdout.txt", stdout);
            uploadCompleteLog(process.getCommandName()+"-"+"stderr.txt", stderr);
        }

        // FIXME: instead of retrieving the full log files it would be faster to accumulate the
        //  incremental fragments already retrieved above.
        if (!process.wasFailure()) {
            if (!stderr.isEmpty()) {
                // Any stderr output is not the wanted result because usually it is a side
                // effect of the remote process' failure, but combined with a non-raised fault
                // flag it may mean a bug (a failure to fail) in Terraform or in the resource
                // provider code, hence report this separately to make it easier to relate.
                log("Spurious remote stderr:\n" + stderr);
            }
        } else {
            final String message = String.format("Error in %s: %s", process.getCommandName(), process.getErrorString());
            log(message);
            log(stderr.isEmpty() ? "(Remote stderr is empty.)" : "Remote stderr:\n" + stderr);
            log(stdout.isEmpty() ? "(Remote stdout is empty.)" : "Remote stdout:\n" + stdout);
            throw ConnectorHandlerFailures.handled(message+"; see logs for more detail.");
        }
        return false;
    }

    // This call actually consists of two network transfers, hence for large files is more
    // likely to time out. However, splitting it into two FSM states would require some place
    // to keep the downloaded file. The callback context isn't intended for that, neither is
    // the lambda's runtime filesystem.
    // There would be one more transfer if the CloudFormation template defines any Terraform
    // variables, so the above note would apply even more.
    protected final void getAndUploadConfiguration(boolean firstTime) throws IOException {
        remoteTerraformProcess().uploadConfiguration(getParameters().getConfiguration(model), model.getVariables(), firstTime);
    }

    private Optional<String> downloadLog(String objectSuffix) {
        String bucketName = callbackContext.getLogBucketName();
        if (bucketName!=null) {
            BucketUtils bucketUtils = new BucketUtils(proxy);
            final String objectKey = callbackContext.getCommandRequestId()+"/"+objectSuffix;
            try {
                return Optional.of(new String(bucketUtils.download(bucketName, objectKey)));
            } catch (Exception e) {
                log(String.format("Failed to retrieve log file %s from S3 bucket %s: %s (%s)", objectKey, bucketName, e.getClass().getName(), e.getMessage()));
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
    }
    protected void uploadCompleteLog(String objectSuffix, String text) {
        String bucketName = callbackContext.getLogBucketName();
        if (bucketName!=null) {
            BucketUtils bucketUtils = new BucketUtils(proxy);
            final String objectKey = callbackContext.getCommandRequestId()+"/"+objectSuffix;
            try {
                bucketUtils.upload(bucketName, objectKey, RequestBody.fromString(text), "text/plain");
                log(String.format("Uploaded a file to s3://%s/%s", bucketName, objectKey));
            } catch (Exception e) {
                log(String.format("Failed to put log file %s into S3 bucket %s: %s (%s)", objectKey, bucketName, e.getClass().getName(), e.getMessage()));
            }
        }
    }
}
