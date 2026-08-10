VERSION 0.8

ARG --global ALL_BUILD_TARGETS="runtime"

ARG --global DOCKER_IMAGE_PREFIX="cardano-x402-facilitator"
ARG --global DOCKER_IMAGES_EXTRA_TAGS=""
ARG --global DOCKER_REGISTRIES="docker.io/cardanofoundation"
ARG --global GITLAB_MAVEN_REGISTRY_URL=""
ARG --global PUSH=false

all:
  LOCALLY
  ARG RELEASE_TAG
  FOR image_target IN $ALL_BUILD_TARGETS
    BUILD +${image_target} --RELEASE_TAG=${RELEASE_TAG}
  END

docker-publish:
  ARG EARTHLY_GIT_SHORT_HASH
  ARG RELEASE_TAG
  WAIT
    BUILD +all --RELEASE_TAG=${RELEASE_TAG}
  END
  LOCALLY
  LET IMAGE_NAME = ""
  FOR registry IN $DOCKER_REGISTRIES
    FOR image_target IN $ALL_BUILD_TARGETS
      SET IMAGE_NAME = ${DOCKER_IMAGE_PREFIX}-${image_target}
      IF [ ! -z "$DOCKER_IMAGES_EXTRA_TAGS" ]
        FOR image_tag IN $DOCKER_IMAGES_EXTRA_TAGS
          RUN echo docker tag ${IMAGE_NAME}:latest ${registry}/${IMAGE_NAME}:${image_tag} && \
            docker tag ${IMAGE_NAME}:latest ${registry}/${IMAGE_NAME}:${image_tag}
          RUN if [ "$PUSH" = "true" ]; then docker push ${registry}/${IMAGE_NAME}:${image_tag}; fi
        END
      END
      RUN echo docker tag ${IMAGE_NAME}:latest ${registry}/${IMAGE_NAME}:${EARTHLY_GIT_SHORT_HASH} && \
        docker tag ${IMAGE_NAME}:latest ${registry}/${IMAGE_NAME}:${EARTHLY_GIT_SHORT_HASH}
      RUN if [ "$PUSH" = "true" ]; then docker push ${registry}/${IMAGE_NAME}:${EARTHLY_GIT_SHORT_HASH}; fi
    END
  END

runtime:
  ARG EARTHLY_TARGET_NAME
  FROM DOCKERFILE -f Dockerfile --target ${EARTHLY_TARGET_NAME} --build-arg GITLAB_MAVEN_REGISTRY_URL .
  SAVE IMAGE ${DOCKER_IMAGE_PREFIX}-${EARTHLY_TARGET_NAME}:latest
