#!/bin/bash
# LocalStack 준비 완료(ready.d) 시 실행 — 개발용 S3 버킷을 생성한다.
set -e

awslocal s3 mb s3://routinely-dev || true
awslocal s3api put-bucket-acl --bucket routinely-dev --acl public-read

echo "✅ LocalStack S3 bucket 'routinely-dev' ready"
