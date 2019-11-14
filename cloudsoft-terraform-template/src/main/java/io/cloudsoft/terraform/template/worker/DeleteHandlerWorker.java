package io.cloudsoft.terraform.template.worker;

import com.amazonaws.cloudformation.proxy.AmazonWebServicesClientProxy;
import com.amazonaws.cloudformation.proxy.Logger;
import com.amazonaws.cloudformation.proxy.OperationStatus;
import com.amazonaws.cloudformation.proxy.ProgressEvent;
import com.amazonaws.cloudformation.proxy.ResourceHandlerRequest;
import io.cloudsoft.terraform.template.CallbackContext;
import io.cloudsoft.terraform.template.DeleteHandler;
import io.cloudsoft.terraform.template.RemoteSystemdUnit;
import io.cloudsoft.terraform.template.ResourceModel;

import java.io.IOException;

public class DeleteHandlerWorker extends AbstractHandlerWorker {
    public enum Steps {
        DELETE_INIT,
        DELETE_ASYNC_TF_DESTROY,
        DELETE_SYNC_RMDIR,
        DELETE_DONE
    }

    public DeleteHandlerWorker(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final Logger logger,
            final DeleteHandler deleteHandler) {
        super(proxy, request, callbackContext, logger, deleteHandler);
    }

    public ProgressEvent<ResourceModel, CallbackContext> call() {
        logger.log(getClass().getName() + " lambda starting: " + model);

        try {
            Steps curStep = callbackContext.stepId == null ? Steps.DELETE_INIT : Steps.valueOf(callbackContext.stepId);
            RemoteSystemdUnit tfDestroy = new RemoteSystemdUnit(this.handler, this.proxy, "terraform-destroy", model.getName());
            switch (curStep) {
                case DELETE_INIT:
                    advanceTo(Steps.DELETE_ASYNC_TF_DESTROY.toString());
                    tfDestroy.start();
                    break;
                case DELETE_ASYNC_TF_DESTROY:
                    if (tfDestroy.isRunning())
                        break; // return IN_PROGRESS
                    if (tfDestroy.wasFailure())
                        throw new IOException("tfDestroy returned errno " + tfDestroy.getErrno());
                    advanceTo(Steps.DELETE_SYNC_RMDIR.toString());
                    break;
                case DELETE_SYNC_RMDIR:
                    advanceTo(Steps.DELETE_DONE.toString());
                    tfSync.onlyRmdir();
                    break;
                case DELETE_DONE:
                    logger.log(getClass().getName() + " completed: success");
                    return ProgressEvent.<ResourceModel, CallbackContext>builder()
                            .resourceModel(model)
                            .status(OperationStatus.SUCCESS)
                            .build();
                default:
                    throw new IllegalStateException("invalid step " + callbackContext.stepId);
            }
        } catch (Exception e) {
            logException (getClass().getName(), e);
            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                    .resourceModel(model)
                    .status(OperationStatus.FAILED)
                    .build();
        }

        logger.log(getClass().getName() + " lambda exiting, callback: " + callbackContext);
        return ProgressEvent.<ResourceModel, CallbackContext>builder()
                .resourceModel(model)
                .callbackContext(callbackContext)
                .callbackDelaySeconds(nextDelay(callbackContext))
                .status(OperationStatus.IN_PROGRESS)
                .build();
    }
}
