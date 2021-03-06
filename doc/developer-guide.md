# Developer guide

## Prerequisites

The build environment is described in the 
[CloudFormation CLI](https://docs.aws.amazon.com/cloudformation-cli/latest/userguide/what-is-cloudformation-cli.html) docs.  The
following [prerequisites](https://docs.aws.amazon.com/cloudformation-cli/latest/userguide/what-is-cloudformation-cli.html#resource-type-setup-java)
(as described) are needed:

* Python 3.6 or later
* Java and Maven
* [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/cli-chap-install.html)
* [CFN CLI](https://github.com/aws-cloudformation/cloudformation-cli) including the `cloudformation-cli-java-plugin`

These resources are not needed to build but are useful to develop and test custom resource providers:

* [Lombok](https://projectlombok.org/) support for your IDE
  (if you want your IDE to understand the Lombok Java annotations)
* [AWS SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/serverless-sam-cli-install.html)
  and Docker (if you want to run serverless unit tests)


## Build and Run

To build and run this project, clone it to your machine and then:

1. Build with: 
   ```sh
   mvn clean package
   ```
1. Register in CloudFormation with:
   ```sh
   cfn submit --set-default -v
   ```
1. Set the parameters in parameter store. We suggest copying the file `setup.yaml`
   to `setup-local.yaml` (which is `.gitignore`d) and editing the values to connect
   to your Terraform server as described in the [installation guide](installation-guide.md),
   then creating the stack:

```sh
aws cloudformation create-stack \
    --template-body "file://setup-local.yaml" \
    --stack-name CloudsoftTerraformInfrastructureSetup \
    --capabilities CAPABILITY_IAM
```


## Testing

### Unit tests

Unit tests are ran as part of the Maven build. To run only the test, execute:
```sh
mvn clean test
```

### Integration tests

Integration test uses SAM local to simulate handlers execution of the connector by CloudFormation. These are triggered by
passing an event in a form of JSON payload to the `TestEntrypoint`, which will tell the connector which handler to use.

The JSON payload must contain the `Cloudsoft::Terraform::Infrastructure` properties within `desiredResourceState`. Provided events
 (i.e. `create.json`, `update.json` and `delete.json`) all use the `ConfigurationUrl` but you can use any property defined
 in the [user guide](./user-guide.md#syntax).
 
 To run the tests:
 1. In one terminal (we'll call this the _SAM_ terminal), start SAM local lambda: `sam local start-lambda`
 2. In another terminal (we'll call this the _cfn_ terminal), run: 
    `cfn invoke --max-reinvoke 10 {CREATE,READ,UPDATE,DELETE,LIST} path/to/event.json`
    
    For instance to do a full cycle of the tests for this project, execute each of the following commands:
    ```sh
    cfn invoke --max-reinvoke 10 CREATE ./sam-tests/create.json
    cfn invoke --max-reinvoke 10 READ ./sam-tests/read.json
    cfn invoke --max-reinvoke 10 UPDATE ./sam-tests/update.json
    cfn invoke --max-reinvoke 10 READ ./sam-tests/read.json
    cfn invoke --max-reinvoke 10 DELETE ./sam-tests/delete.json
    ```
    Log output will be shown in the _SAM_ terminal, whereas the _cfn_ terminal will show the
    input and output to the connector lambdas. Each command should conclude with a `SUCCESS` status.
    
    To successfully execute the cycle locally, you'll have to grab the `resource identifier` from the initial `CREATE` test.
    In each of the `read|update|delete.json` event files, replacing the `<resource_identifier>` placeholder.
    This value can be found in the initial logging for the `CREATE` call in the _SAM_ terminal:
    ```text
    [CREATE] invoking handler...
    Init complete
    io.cloudsoft.terraform.infrastructure.CreateHandler$Worker <snip>
    ---
    io.cloudsoft.terraform.infrastructure.CreateHandler$Worker <snip>
    Stack resource model identifier set as: 20210804-063602-CALkoVcr
    ```
    or in most handler responses in the _cfn_ terminal in the `resourceModel/Identifier` field:
    ```text
        "status": "IN_PROGRESS",
        "message": "Step: CREATE_LOG_TARGET Logs are available at https://s3.console.aws.amazon.com/s3/buckets/<snip>/20210804-063602-CALkoVcr/.",
        "callbackContext": {
            "commandRequestId": "20210804-063622-UVjrEQ",
            "stepId": "CREATE_RUN_TF_INIT",
            "lastDelaySeconds": 0,
            "logBucketName": "<snip>",
            "createdModelIdentifier": "20210804-063602-CALkoVcr"
        },
        "callbackDelaySeconds": 0,
        "resourceModel": {
            "Identifier": "20210804-063602-CALkoVcr",
            "LogBucketUrl": "https://s3.console.aws.amazon.com/s3/buckets/<snip>/20210804-063602-CALkoVcr/",
            "ConfigurationUrl": "https://raw.githubusercontent.com/cloudsoft/aws-cfn-connector-for-terraform/master/sam-tests/s3-step1.tf"
        }
    ```
   
    You can specify `--region` to run the test in a specific region.
 
    These tests require the Terraform server to be up and running, as well as the parameters set in parameter store.  See 
    [prerequisites](./installation-guide.md#prerequisites) and [step 3 of the installation guide](./installation-guide.md#installation).


### End-to-end tests

Once the connector is built and submitted to AWS:

1. Deploy some `Cloudsoft::Terraform::Infrastructure` resource, e.g. the file `terraform-example.cfn.yaml`:
   ```sh
   aws cloudformation create-stack --template-body file://terraform-example.cfn.yaml --stack-name terraform-example
   ```
2. Delete it when you're done:
   `aws cloudformation delete-stack --stack-name terraform-example`

_Note these tests require the a Terraform server to be up and running, as well parameters to be set in parameter store.
See [prerequisites](./installation-guide.md#prerequisites) and [step 3 of the installation guide](./installation-guide.md#installation)._


## Troubleshooting

The `aws` and `cfn` tools and maven repository seem at present (Summer 2020) to be updated quite frequently
in ways that break backwards compatibility with respect to building this project or sometimes using this project.
If errors are encountered it is recommented that you update to the latest version,
following the instructions [here](https://docs.aws.amazon.com/cloudformation-cli/latest/userguide/what-is-cloudformation-cli.html),
summarized below for convenience:

    brew install python awscli
    pip3 install --upgrade cloudformation-cli-java-plugin
    # then update the RPDK verisonin the pom.xml here and fix any compilation breakages


## Open Features aka Limitations

Some features we'd like to support include:

* Implement the "List" operation

* Make "Read" more efficient by being able to cache outputs from the last execution
  (rather than need to SSH to ask about outputs on each run)

* Getting individual outputs, e.g. using `!GetAtt TerraformExample.Outputs.custom_output1.value`.
  This project currently returns the map in `Outputs`, but CFN does not support accessing it.

* More download options: providing credentials, supporting Git, supporting downloads by the Terraform server.
  Let us know what you'd like to see!

* Spinning up a Terraform worker server as needed and maintaining state in S3,
  so the connector does not require a pre-existing TF server.
  (But the current implementation is more flexible, as it allows you to configure TF the way you wish.)

* Supporting multiple connector instances in the same account, pointing at different TF servers.

* Being more forgiving of transient network errors where possible (eg when checking status of a long-running command execution)

And technical debt:

* The build insists on overwriting the `docs/` folder, even if the `README.md` exists, ever since upgrading `cfn`.
  Seems it is `cfn generate` which does this, with no option to disable it.
  For now the docs are in `doc/` instead, with `docs/` ignored in `.gitignore`.

Contributions are welcome!

