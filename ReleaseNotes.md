## 0.1.2

- Update to AWS SDK 1.8.3
  AWS SDK 1.8.3 includes the t2.small and t2.medium instance types

- Fix glacier, sqs, autoscaling and rds

- Remove common keys from request object arglists
  Removes the :dry-run-request and :request-metric-collector keys from the
  arglist

## 0.1.1

- Fix runtime coercions
  Fixes #2

- Use fipp to speed up pprint
  Closes #3

- Remove redefinition warnings in ec2 api
  Fixes #4

- Special case generation of InstanceType beans

# 0.1.0

- Initial Version
