## Unreleased

## v0.30.0 (2025-06-19)

- Fix a possible infinite loop when checking the state of
  a change set that failed to create. This can happen when
  the change set's name is invalid.
- Throw an error immediately when a change set for a new stack
  fails to create.
- Add `:donut.system/config-schema` values to components.
  These can be used by donut.system's built-in
  validation plugin.
- Allow nil values for optional keys in template config-schema.
- Fix change sets not being applied if they did not change any
  resources but did change an output.
  - Add `:execution-status` to change set instance to support this.
- Update `salmon.ecr/get-auth-token` to work with the response
  format for public ECR repositories.
- Add an `:ecr-api` option to `salmon.ecr/push-group` to allow
  using the `:ecr-public` API instead of the default `:ecr` API.

## v0.29.1 (2025-06-17)

- Add missing `rs.shaffe/sys-ext` dependency.
  This is used by `salmon.ecr`.

## v0.29.0 (2025-06-02)

- Add `salmon.util/b64-decode`.
- Add `salmon.docker` namespace with convenience functions for
  building, tagging, and pushing docker images.
- Add `salmon.ecr` namespace with convenience functions for
  logging in to ECR and pushing docker images to ECR.

## v0.28.0 (2025-05-30)

- Add `:aws-client-opts` option to `change-set`, `stack`, and
  `stack-properties` components. This allows providing additional
  config to the AWS client.

## v0.27.0 (2025-05-02)

- (deprecation) Rename `:client` config option
  to `:cloudformation-client` for `change-set`, `stack`, and
  `stack-properties` components.
  `:client` is still accepted, but is deprecated.
  Salmon is normally used in systems that call multiple AWS services.
  With this change, you can construct a single opts map with keys like
  `:cloudformation-client` and `:s3-client` that can be passed
  directly to various components.
  - Note: As of v0.28.0, it is recommended to pass `:aws-client-opts`
   rather than a specific client instance. This is because
   creating individual clients can become cumbersome.
- (deprecation) Deprecate `:client` instance key for
  `change-set`, `stack`, and `stack-properties` components.
  The new key is `:cloudformation-client`. `:client` is still
  present, but may be removed in the future.
- Add stack name to "creating change-set" log message.
- Add op-map to ex-data when CreateChangeSet fails.
- Add `:throw-on-missing?` option to `stack-properties` component.
- Add `:region` to stack and stack-properties instances.
- Add `:status` to stack and stack-properties instances.

## v0.26.0 (2025-03-31)

- (breaking) Throw exceptions for errors in fetch-hosted-zone-id
  instead of returning the anomaly response from aws-api. This
  is more appropriate for how the function is typically used.
  Its result is often fed directly into data structures,
  so callers would have to provide their logic to throw exceptions
  if we did not.
- Add `:resource-ids` map to stack and stack-properties instances.

## v0.25.1 (2025-02-28)

- Don't call ExecuteChangeSet on stack start when there are no
  changes in the change set. Fix a spurious error when using a
  change set with no changes on a stack in an
  `UPDATE_ROLLBACK_COMPLETE` state.

## v0.25.0 (2025-02-26)

- Add :template-url option to change-set and stack components.

## v0.24.0 (2025-02-22)

- (breaking) Change the behavior when updating a stack from a change-set
  to match the behavior when creating a stack from a template.
  - If the stack is in an IN_PROGRESS state when attempting to update,
    wait for it to complete before updating. This produced an error before.
  - After updating, wait for the update to complete and throw an error if
    the update fails. Previously, this was not waited on and the update
    was not checked for failure.
- (breaking) When a region is specified, pass it to cfn-lint. This may
  cause some templates to fail linting that previously passed.
  This is unlikely to adversely affect anyone because the change-sets or
  stacks using those templates would very likely have been failing to
  deploy.
- Add `salmon.cloudformation/template` component. This is useful for
  validating a template that is used by more than one stack or change-set.

## v0.23.2 (2025-02-20)

- Fix that `salmon.signal/early-validate/conf` would not
  validate properly when refs were in the component's config.

## v0.23.1 (2025-02-18)

- Fix stack early-validate failing when using a change-set

## v0.23.0 (2025-02-18)

- (breaking) Fix that `salmon.util/->ex-info` should return an
  ex-info, not throw it.
- Fix a bug where stack and change set names could not be refs.
- When attempting to delete a change set and the stack or change
  set no longer exists, return early rather than throwing an error.
  This is a common situation because in the most natural
  configuration, the stack will receive a delete signal before the
  change set does. When the change set attempts to delete itself,
  it will find that the stack has already been deleted.
- Support creating change sets for stacks that do not exist yet.
  Previously, change sets could only be created for stacks that
  already existed.
- Fix an issue where stack early validation would not run if
  the :change-set option was present.

## v0.22.0 (2025-02-14)

- Add `salmon.cloudformation/change-set` to create and manage change sets
  for cloudformation stacks. This makes it easier to analyze what
  changes will be made to a stack.
- Add `:change-set` option to `salmon.cloudformation/stack`. This allows
  providing a change set as the source of stack updates instead of
  providing parameters and a template.
- Change the behavior of a stack receiving a :salmon/delete signal
  when the stack has already been deleting. Previously, it could throw
  an exception. This has been changed so that no exception is thrown
  and the signal completes successfully.

## v0.21.0 (2025-02-10)

- Update `salmon.util/aws-error-code` and `salmon.util/aws-error-message`
  to find error codes and messages in responses that return a
  top-level :Error map, such as the response to :DeleteBucket.
  They would previously return nil for these responses.
- Add `salmon.cleanup/full-delete-all-stacks!` for deleting
  resources in test accounts
- Add `:termination-protection?` option to `salmon.cloudformation/stack`.
  This can be used to enable or disable termination protection.  

## v0.20.0 (2024-12-12)

- Add `salmon.util/pages-seq` for handling paginated responses.
- Add `salmon.cleanup/deregister-all-amis!` for cleaning up AMIs in test accounts.
- Add `salmon.cleanup/delete-orphaned-snapshots!` for cleaning up snapshots in test accounts.
- Use `:cognitect.aws.error/code` to find the error code in `salmon.util/aws-error-code`.
- If there is no message, use the error code in an ex-info created by `salmon.util/->ex-info`.

## v0.19.0 (2024-09-16)

- Fix a case where ExceptionInfos generated from aws-api invocations were missing a message.
  This could happen when the error occurred before making a request to AWS, such as when aws-api cannot find valid credentials.

## v0.18.0 (2024-08-22)

- Fix error that occurred when a stack in UPDATE_ROLLBACK_COMPLETE state was started with no changes requested. This will now succeed with no errors.
- (potentially breaking) Upgrade to clojure 1.12.0-rc1 from 1.12.0-alpha5.

## v0.17.0 (2024-05-03)

- Improve error messages when a stack is in a rollback state by adding the event that caused the failure.
- Fix "Updating stack" log message was missing the stack name

## v0.16.0 (2024-04-11)

- Add logging messages when creating, updating, or deleting a stack, and when waiting for a stack to enter a new state.
- (breaking) When a CloudFormation stack fails during creation, it enters the ROLLBACK_COMPLETE status. Previously, salmon would fail during stack start when the named stack is in ROLLBACK_COMPLETE status. Now salmon deletes the stack and creates a new stack with the same name.
- Upgrade deps

## v0.21.0 (2025-02-10)

- Update `salmon.util/aws-error-code` and `salmon.util/aws-error-message`
  to find error codes and messages in responses that return a
  top-level :Error map, such as the response to :DeleteBucket.
  They would previously return nil for these responses.
- Add `salmon.cleanup/full-delete-all-stacks!` for deleting
  resources in test accounts
- Add `:termination-protection?` option to `salmon.cloudformation/stack`.
  This can be used to enable or disable termination protection.  

## v0.20.0 (2024-12-12)

- Add `salmon.util/pages-seq` for handling paginated responses.
- Add `salmon.cleanup/deregister-all-amis!` for cleaning up AMIs in test accounts.
- Add `salmon.cleanup/delete-orphaned-snapshots!` for cleaning up snapshots in test accounts.
- Use `:cognitect.aws.error/code` to find the error code in `salmon.util/aws-error-code`.
- If there is no message, use the error code in an ex-info created by `salmon.util/->ex-info`.

## v0.19.0 (2024-09-16)

- Fix a case where ExceptionInfos generated from aws-api invocations were missing a message.
  This could happen when the error occurred before making a request to AWS, such as when aws-api cannot find valid credentials.

## v0.18.0 (2024-08-22)

- Fix error that occurred when a stack in UPDATE_ROLLBACK_COMPLETE state was started with no changes requested. This will now succeed with no errors.
- (potentially breaking) Upgrade to clojure 1.12.0-rc1 from 1.12.0-alpha5.

## v0.17.0 (2024-05-03)

- Improve error messages when a stack is in a rollback state by adding the event that caused the failure.
- Fix "Updating stack" log message was missing the stack name

## v0.16.0 (2024-04-11)

- Add logging messages when creating, updating, or deleting a stack, and when waiting for a stack to enter a new state.
- (breaking) When a CloudFormation stack fails during creation, it enters the ROLLBACK_COMPLETE status. Previously, salmon would fail during stack start when the named stack is in ROLLBACK_COMPLETE status. Now salmon deletes the stack and creates a new stack with the same name.
- Upgrade deps

- Add logging messages when creating, updating, or deleting a stack, and when waiting for a stack to enter a new state.
- (breaking) When a CloudFormation stack fails during creation, it enters the ROLLBACK_COMPLETE status. Previously, salmon would fail during stack start when the named stack is in ROLLBACK_COMPLETE status. Now salmon deletes the stack and creates a new stack with the same name.

## v0.15.0 (2024-02-24)

- Remove private `salmon.construct` code. This code has been moved to the [salmon.construct](https://github.com/john-shaffer/salmon.construct) repo.
  - Remove `io.staticweb/cloudformation-templating` dependency.
- Fix `salmon.test/bucket-name-schema` should prohibit dashes next to periods.
- (breaking) Throw an exception if a stack enters a rollback status when updating it. This condition previously did not throw and behaved the same as a successful update.
- Cloudformation stack delete will now wait on the stack to exit IN_PROGRESS states before attempting to delete. Previously, the delete would fail.
- (breaking) Remove use of ->error, ->info, and ->validate since donut.system has removed them. Replace with clojure.tools.logging and exceptions.
  - Add `clojure.tools.logging` as a direct dependency.
- (breaking) Remove salmon.signal/signal!, delete!, early-validate!, start!, and start!. They are no longer needed due to the removal of ->error, ->info, and ->validate.
- Fix an issue where the `:lint?` stack option was ignored.

## v0.14.0 (2024-01-16)

- (breaking) Rename `salmon.cleanup/delete-all!` to `delete-all-stacks!` and add required argument `:regions`.
- Add `salmon.resource.certificate/dns-validated`. This creates a
  resource that defines an automatically-validated AWS Certificate Manager
  (ACM) certificate.
- Add `salmon.construct.static-site.simple`. This provides a single
  high-performance static website backed by S3 and CloudFront. Note: This is not stable and can only be accessed via a private var.
  - Add `io.staticweb/cloudformation-templating` as a dependency.
- When a CloudFormation stack is in an `IN_PROGRESS` state and a start or
  delete signal is attempted, stacks would previously return an error.
  They now wait for a `COMPLETE` state before trying to apply the signal again.
- Add `salmon.util` functions `->ex-info` and `invoke!` for calls to the
  AWS API that should throw exceptions on failure.
- Fix pagination of stack resources not working.
- Fix `salmon.util` functions `aws-error-code` and `aws-error-message` parsing of
  errors found in `(-> response :Response :Errors)` rather than
  `(-> response :ErrorResponse)`.
- Add `salmon.ec2/list-orphaned-snapshots` to list snapshots that can
  safely be deleted.
    - Add `com.cognitect.aws/ec2` as a dependency.
- Add `salmon.uberjar` namespace for building deployable uberjars.
    - Add `io.github.clojure/tools.build` as a dependency.
- Add `salmon.ssm` namespace for AWS Systems Manager operations. This is currently used to fetch the Debian AMI IDs used in tests.
    - Add `com.cognitect.aws/ssm` as a dependency.
- Add `salmon.packer` namespace for building AMIs.
    - Require Clojure version 1.12.0-alpha5 so that the new `clojure.java.process` namespace can be used. This is preferable to adding a new dependency.
    - Add `org.clojure/data.csv` as a dependency.

## v0.13.0 (2023-11-04)

- Add `salmon.util` functions `full-name`, `tags`, and `resource`.
  These are useful when building CloudFormation templates.
- Add `:tags` option to stack for defining stack-level tags.
- Add `:tags` and `:tags-raw` maps to stack and stack-properties
  instances.
- Upgrade to `party.donut/system` 0.0.218.
- Fix a case where hosted zone "matches" returned by AWS don't
  necessarily match the query.
- Add `salmon.s3/upload!` for uploading to a bucket.
  - Add `com.cognitect.aws/s3` as a dependency.

## v0.12.0 (2023-11-01)

- Add `:client` and `:region` options to stack and stack-properties
  components.
- Move `anomaly?`, `aws-error-code`, and `aws-message-fns` to `salmon.util`
  and make them public.
- Add `salmon.route53/fetch-hosted-zone-id` as a simple way to look up
  which hosted zone id to attach RecordSets and RecordSetGroups to.
  - Add `com.cognitect.aws/route53` as a dependency.
- Upgrade to `party.donut/system` 0.0.215.

## v0.11.0 (2023-08-01)

- (breaking) Change the format of a stack's `:resources` from a seq to a map with
  each resource's LogicalResourceId as keys. This allows individual resources to be
  the target of donut.system refs.
  (e.g., `(ds/local-ref [:MyStack :resources :MyBucket :PhysicalResourceId])`)
- Handle custom resolution fns via `:donut.system/resolve-refs`. Custom
  resolution fns will prevent early validation.

## v0.10.1 (2023-07-29)

- Fix incorrect resolution of local-refs (again).

## v0.10.0 (2023-07-28)

- Fix incorrect resolution of local-refs.
- (breaking) `salmon.validation/resolve-refs` now takes a 
  `referencing-component-id` argument so that it can handle local-refs. 
- Add `:parameters` and `:parameters-raw` maps to stack and stack-properties
  instances.
- Add `:describe-stack-raw` to stack and stack-properties instances. This provides
  access to the raw DescribeStack response.
- Fix missing part of error message in `salmon.signal` fns.

## v0.9.0 (2023-07-08)

- Fix a bug in salmon.signal/early-validate-conf that caused it to fail
  early-schema validation with newer versions of donut.system.
- Update donut.system dependency from club.donutpower/system 0.0.165
  to party.donut/system 0.0.203.
- Handle non-string error and validation messages in salmon.signal/signal!
- Show a humanized error message for early-schema validation errors
- Allow donut.system local-refs in component configs
- (breaking) `salmon.validation/refs-resolveable?` now takes a `component-id` argument so that it can handle local-refs. 
- Add `:name` to stack instance.
- Add salmon.cloudformation/stack-properties to create a component that
  retrieves the properties of an existing CloudFormation stack. Properties
  include resources and outputs.
