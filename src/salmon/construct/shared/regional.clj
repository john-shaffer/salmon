(ns salmon.construct.shared.regional
  "A construct for resources that can be shared across stacks in a region,
   such as buckets, certificates, keys, load balancers, and repositories."
  (:require [io.staticweb.cloudformation-templating :as ct]
            [medley.core :as me]
            [salmon.cloudformation :as cf]
            [salmon.util :as u]))

(defn- ecr-full-access-policy [& {:as opts}]
  (u/resource
    "AWS::IAM::ManagedPolicy"
    {:Description "Grants full access to the ECR repository."
     :PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action
        ; https://docs.aws.amazon.com/AmazonECR/latest/userguide/security-iam-awsmanpol.html#security-iam-awsmanpol-AmazonEC2ContainerRegistryPowerUser
        ["ecr:GetAuthorizationToken"
         "ecr:BatchCheckLayerAvailability"
         "ecr:GetDownloadUrlForLayer"
         "ecr:GetRepositoryPolicy"
         "ecr:DescribeRepositories"
         "ecr:ListImages"
         "ecr:DescribeImages"
         "ecr:BatchGetImage"
         "ecr:GetLifecyclePolicy"
         "ecr:GetLifecyclePolicyPreview"
         "ecr:ListTagsForResource"
         "ecr:DescribeImageScanFindings"
         "ecr:InitiateLayerUpload"
         "ecr:UploadLayerPart"
         "ecr:CompleteLayerUpload"
         "ecr:PutImage"]
        :Effect "Allow"
        :Resource (ct/get-att :ECRRepo :Arn)}]}}
    opts))

(defn- ecr-read-access-policy [& {:as opts}]
  (u/resource
    "AWS::IAM::ManagedPolicy"
    {:Description "Grants read-only access to the ECR repository."
     :PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action
        ; https://docs.aws.amazon.com/AmazonECR/latest/userguide/security-iam-awsmanpol.html#security-iam-awsmanpol-AmazonEC2ContainerRegistryReadOnly
        ["ecr:GetAuthorizationToken"
         "ecr:BatchCheckLayerAvailability"
         "ecr:GetDownloadUrlForLayer"
         "ecr:GetRepositoryPolicy"
         "ecr:DescribeRepositories"
         "ecr:ListImages"
         "ecr:DescribeImages"
         "ecr:BatchGetImage"
         "ecr:GetLifecyclePolicy"
         "ecr:GetLifecyclePolicyPreview"
         "ecr:ListTagsForResource"
         "ecr:DescribeImageScanFindings"]
        :Effect "Allow"
        :Resource (ct/get-att :ECRRepo :Arn)}]}}
    opts))

(defn- ecr-repo [& {:as opts}]
  (u/resource
    "AWS::ECR::Repository"
    {:EncryptionConfiguration
     {:EncryptionType "AES256"}}
    opts))

(defn- shared-resources []
  {:ECRFullAccessPolicy (ecr-full-access-policy)
   :ECRReadAccessPolicy (ecr-read-access-policy)
   :ECRRepo (ecr-repo)})

(defn- stack [& {:keys [lint? name outputs resources region tags]}]
  (cf/stack
    :capabilities #{"CAPABILITY_AUTO_EXPAND" "CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"}
    :lint? lint?
    :name name
    :region region
    :tags tags
    :template
    (->> {:AWSTemplateFormatVersion "2010-09-09"
          :Metadata
          {:SalmonOwner "salmon.construct"}
          :Outputs outputs
          :Resources resources}
      (me/remove-vals nil?))))

(defn ALPHA-group
  "Experimental shared regional resources group. Likely to change."
  [name-prefix & {:as opts}]
  {:shared (-> (assoc opts
                 :name (str name-prefix "Shared")
                 :resources (shared-resources))
             stack)})
