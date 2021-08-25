# Installation guide

The Terraform resource provider for CloudFormation adds a new CloudFormation resource type, `Cloudsoft::Terraform::Infrastructure`, which allows you to deploy a Terraform infrastructure as a part of a CloudFormation stack using a Terraform configuration that is a part of a CloudFormation template.

This page will guide you on how to install the Terraform resource provider for CloudFormation.

## Prerequisites

### Terraform server

The connector requires a running Terraform (version 0.12 or later) server that:
- runs Linux
- can accept SSH connections from AWS Lambda
- is configured with the correct credentials for the target clouds
  (for example, if the Terraform server needs to manage resources through its AWS provider,
  the configured Linux user needs to have a valid `~/.aws/credentials` file, even though
  Terraform does not use AWS CLI)
- has the command-line tools to extract archived Terraform configurations (right now this
  is ZIP, which requires `unzip`, which, for example, can be installed on Ubuntu Linux
  with `apt-get install unzip`)

You can quickly provision a server instance for testing in AWS and install terraform :
- Create a server instance in EC2 using the Amazon Linux 2 AMI.  If you use an existing ssh key, ensure the private 
  key is passwordless.
- Log into the server as `ec2-user` and run `sudo yum -y update && sudo yum -y upgrade`.
- Install terraform (**the connector has been tested with the 0.14.1 version of terraform.  1.x versions are known to not work with the current version of the connector**)
  - Run `sudo yum-config-manager --add-repo https://rpm.releases.hashicorp.com/AmazonLinux/hashicorp.repo`
  - Run `sudo yum -y install terraform-0.14.1` 
- Assign an instance role to the server appropriate for the resources terraform will be provisioning.  To test using a 
  script in this repo, create a role with `AmazonS3FullAccess`.

_(Taken from https://learn.hashicorp.com/tutorials/terraform/install-cli?in=terraform/aws-get-started.  Select
  tabs Linux then Amazon Linux for instructions.)_

### AWS CLI

You will need to have the AWS CLI installed and configured on your local machine. Please [see the documentation](https://docs.aws.amazon.com/cli/latest/userguide/cli-chap-install.html) how to achieve this.

## Installation

1. Download the [`resource-role.yaml`](https://raw.githubusercontent.com/cloudsoft/aws-cfn-connector-for-terraform/master/resource-role.yaml) template 
   and create a stack using the command below.  Note the ARN of the created execution role for use later.
   ```sh
   aws cloudformation create-stack \
     --template-body "file://resource-role.yaml" \
     --stack-name CloudsoftTerraformInfrastructureExecutionRole \
     --capabilities CAPABILITY_IAM
   ```

1. Download the [`setup.yaml`](https://raw.githubusercontent.com/cloudsoft/aws-cfn-connector-for-terraform/master/setup.yaml) template.
   Edit the parameters as needed. [More detail on parameters is below](#configuration-parameters). Note that the following ones (marked `FIXME` in the file) are required.
   
   - `/cfn/terraform/ssh-host`
   - `/cfn/terraform/ssh-username`
   - `/cfn/terraform/ssh-key`
   
1. Create the `setup` stack using the command below. Note the ARN of the created logging role and the log group for use later.
   ```sh
   aws cloudformation create-stack \
     --template-body "file://setup.yaml" \
     --stack-name CloudsoftTerraformInfrastructureSetup \
     --capabilities CAPABILITY_IAM
   ```

1. Download the `Cloudsoft::Terraform::Infrastructure` package and upload it to an s3 bucket:
   ```
   $ wget https://github.com/t-jones/aws-cfn-connector-for-terraform/releases/download/v0.1/cloudsoft-terraform-infrastructure.zip
   # Make an s3 bucket if needed then upload the package to it
   $ aws s3 mb s3://<bucket_name>
   $ aws s3 cp cloudsoft-terraform-infrastructure.zip s3://<bucket>
   ```

1. Register the `Cloudsoft::Terraform::Infrastructure` CloudFormation type, using the command below, with info from 
   resources created above.  
   
   CloudFormation outputs can be retrieved from the console or with the following commands:
    ```
    $ export EXECUTION_ROLE_ARN=$(aws cloudformation describe-stacks --stack-name CloudsoftTerraformInfrastructureExecutionRole --query "Stacks[0].Outputs[?OutputKey=='ExecutionRoleArn'].OutputValue" --output text) && echo EXECUTION_ROLE_ARN=$EXECUTION_ROLE_ARN
    $ export LOGGING_ROLE_ARN=$(aws cloudformation describe-stacks --stack-name CloudsoftTerraformInfrastructureSetup --query "Stacks[0].Outputs[?OutputKey=='LoggingRoleArn'].OutputValue" --output text) && echo LOGGING_ROLE_ARN=$LOGGING_ROLE_ARN
    $ export LOG_GROUP_NAME=$(aws cloudformation describe-stacks --stack-name CloudsoftTerraformInfrastructureSetup --query "Stacks[0].Outputs[?OutputKey=='LogGroup'].OutputValue" --output text) && echo LOG_GROUP_NAME=$LOG_GROUP_NAME
   ```   

    Then deploy the CloudFormation extension (update <bucket_name> to the name of the bucket where you copied the extension):

   ```sh
    aws cloudformation register-type \
      --type RESOURCE \
      --type-name Cloudsoft::Terraform::Infrastructure \
      --schema-handler-package s3://<bucket_name>/cloudsoft-terraform-infrastructure.zip \
      --execution-role-arn $EXECUTION_ROLE_ARN \
      --logging-config "{\"LogRoleArn\":\"$LOGGING_ROLE_ARN\",\"LogGroupName\": \"$LOG_GROUP_NAME\"}"
   ```
   
    Status of the deployment can be checked with `aws cloudformation describe-type-registration --registration-token <RegistrationToken>`.
    `RegistrationToken` is returned from the initial call. 
   
   If you are updating the extension, note the version number and use the following command to set the default version:
   ```sh
   aws cloudformation set-type-default-version --type RESOURCE --type-name Cloudsoft::Terraform::Infrastructure --version-id <version_number>
   ```
   
   Once deployment is complete, the extension can be viewed in the AWS CloudFormation console under _Registry->Activated Extensions_ and then 
   filtering on "Privately registered".

### Testing the installation

Installation can be tested with the `terraform-example.cfn.yaml`  CloudFormation script in the root of this repository.
 - Download the file locally.
 - Update the AWS region in the embedded terraform script if desired.  Also, remove or update the `LogBucketName` parameter
   for the `TerraformExample` resource.  See the description for `/cfn/terraform/logs-s3-bucket-name` below or 
   [`LogBucketName`](user-guide.md#properties) in the user doc.
 - Create a CloudFormation stack in the console.  Upload the template when prompted.
 - When prompted, randomize the bucket names being passed as parameters to the stack.

## Configuration Parameters

This resource provider (RP) uses the following parameters:

   - `/cfn/terraform/ssh-host` (required): the hostname or the IP address of the Terraform server
   
   - `/cfn/terraform/ssh-username` (required): the user as which the RP should SSH
   
   - `/cfn/terraform/ssh-key` (required): the SSH key with which the RP should SSH
    
   - `/cfn/terraform/ssh-port` (defaults to 22): the port to which the RP should SSH
   
   - `/cfn/terraform/ssh-fingerprint` (optional): the fingerprint of the Terraform server, for security.
     The value must be in one of the
     [fingerprint formats supported in SSHJ](https://github.com/hierynomus/sshj/blob/master/src/main/java/net/schmizz/sshj/transport/verification/FingerprintVerifier.java#L33).
     For example, a SHA-256 fingerprint of the Ed25519 SSH host key of the current host
     can be computed with `ssh-keygen -E sha256 -lf /etc/ssh/ssh_host_ed25519_key.pub | cut -d' ' -f2`.
    
   - `/cfn/terraform/process-manager` (optional): the server-side remote persistent execution mechanism to use,
     either `nohup` (default) or `systemd`. In the latter case the server
     must run a Linux distribution that uses systemd with support for user mode and linger
     (typically CentOS 8, Fedora 28+, Ubuntu 18.04+, but not Amazon Linux 2)
        
   - `/cfn/terraform/logs-s3-bucket-name` (optional): if set, all Terraform logs are shipped to an S3
     bucket and returned in output and in error messages.
     This value is as per the `LogBucketName` property on the resource;
     see the documentation on that property in the [user guide](user-guide.md#properties).
     If that property is set it will override any value set here.

Where a parameter is optional, it can be omitted or the special value `default` can be set to tell the RP
to use the default value.  Note that omitting it causes warnings in the CloudWatch logs 
(which we cannot disable, though in this case they are benign), and it is not permitted so leave it blank:
this is why the special value `default` is recognized by this RP, as used in `setup.yaml`.
  
