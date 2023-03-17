#!/bin/bash
#
# A script that takes a prebuilt docker image, and pushes it to the GATK release repositories on
# dockerhub and GCR
#
# Usage: release_prebuilt_docker_image.sh <prebuilt_image> <version_tag_for_release>
#
# prebuilt_image: The pre-built image you want to release (make sure you've tested it!)
# version_tag_for_release: The version of GATK you're releasing (eg., 4.0.0.0)
#

if [ $# -ne 2 ]; then
  echo "Usage: $0 <prebuilt_image> <version_tag_for_release>"
  exit 1
fi

PREBUILT_IMAGE="$1"
VERSION="$2"
DOCKERHUB_REPO="broadinstitute/gatk"
GCR_REPO="us.gcr.io/broad-gatk/gatk"

function fatal_error() {
  echo "$1" 1>&2
  exit 1
}

function docker_push() {
  echo "Pushing to ${1}"
  docker push "${1}"
  if [ $? -ne 0 ]; then
    fatal_error "Failed to push to ${1}"
  fi
}

docker pull "${PREBUILT_IMAGE}"
if [ $? -ne 0 ]; then
    fatal_error "Failed to pull pre-built image ${PREBUILT_IMAGE}"
fi

docker tag "${PREBUILT_IMAGE}" "${DOCKERHUB_REPO}:${VERSION}"
docker tag "${DOCKERHUB_REPO}:${VERSION}" "${DOCKERHUB_REPO}:latest"
docker tag "${PREBUILT_IMAGE}" "${GCR_REPO}:${VERSION}"
docker tag "${GCR_REPO}:${VERSION}" "${GCR_REPO}:latest"

docker_push "${DOCKERHUB_REPO}:${VERSION}"
docker_push "${DOCKERHUB_REPO}:latest"
docker_push "${GCR_REPO}:${VERSION}"
docker_push "${GCR_REPO}:latest"

exit 0