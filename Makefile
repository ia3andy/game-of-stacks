VERSION := 0.0.1
STRIMZI_VERSION := 0.16.0
ISTIO_VERSION := 1.4.5
# List of all services (for image building / deploying)
NAMESPACES ?= gos-heroes gos-villains gos-arrows gos-catapults gos-web
SERVICES ?= web-j11hotspot villains-j11oj9 catapult-vertx-j11hotspot arrow-j11hotspot hero-native hero-j11hotspot
# Kube's CLI (kubectl or oc)
K8S_BIN ?= $(shell which kubectl 2>/dev/null || which oc 2>/dev/null)
# OCI CLI (docker or podman)
OCI_BIN ?= $(shell which podman 2>/dev/null || which docker 2>/dev/null)
OCI_BIN_SHORT = $(shell if [[ ${OCI_BIN} == *"podman" ]]; then echo "podman"; else echo "docker"; fi)
# Tag for docker images
OCI_TAG ?= dev
# Set MINIKUBE=true if you want to deploy to minikube (using registry addons)
MINIKUBE ?= true

ifeq ($(MINIKUBE),false)
OCI_DOMAIN ?= quay.io
OCI_DOMAIN_IN_CLUSTER ?= quay.io
PULL_POLICY ?= "IfNotPresent"
else ifeq ($(OCI_BIN_SHORT),podman)
OCI_DOMAIN ?= "$(shell minikube ip):5000"
OCI_DOMAIN_IN_CLUSTER ?= localhost:5000
PULL_POLICY ?= "Always"
else
OCI_DOMAIN ?= ""
OCI_DOMAIN_IN_CLUSTER ?= ""
PULL_POLICY ?= "Never"
endif

.ensure-yq:
	@command -v yq >/dev/null 2>&1 || { echo >&2 "yq is required. Grab it on https://github.com/mikefarah/yq"; exit 1; }

DOCKER_ENV = ""

clean:
	mvn clean

build: install

install:
	mvn install -DskipTests

build-native:
	mvn package -f hero/pom.xml -Pnative -Dquarkus.native.container-build=true -DskipTests -Dnative-image.xmx=5g -Dquarkus.native.container-runtime=${OCI_BIN_SHORT};
#	mvn package -f arrow/pom.xml -Pnative -Dquarkus.native.container-build=true -DskipTests -Dnative-image.xmx=5g -Dquarkus.native.container-runtime=${OCI_BIN_SHORT};

test:
	mvn test

docker-eval:
	for svc in ${SERVICES} ; do \
		eval $$(minikube docker-env) ; \
		docker build -t gos/gos-$$svc:${OCI_TAG} -f ./k8s/$$svc.dockerfile ./ ; \
	done

docker:
	for svc in ${SERVICES} ; do \
		${OCI_BIN_SHORT} build -t ${OCI_DOMAIN}/gos/gos-$$svc:${OCI_TAG} -f ./k8s/$$svc.dockerfile ./ ; \
		${OCI_BIN_SHORT} tag ${OCI_DOMAIN}/gos/gos-$$svc:${OCI_TAG} localhost:5000/gos/gos-$$svc:${OCI_TAG} ; \
		${OCI_BIN_SHORT} push ${OCI_DOMAIN}/gos/gos-$$svc:${OCI_TAG} ; \
	done

podman:
	for svc in ${SERVICES} ; do \
		${OCI_BIN_SHORT} build -t ${OCI_DOMAIN}/gos/gos-$$svc:${OCI_TAG} -f ./k8s/$$svc.dockerfile ./ ; \
		${OCI_BIN_SHORT} tag ${OCI_DOMAIN}/gos/gos-$$svc:${OCI_TAG} localhost:5000/gos/gos-$$svc:${OCI_TAG} ; \
		${OCI_BIN_SHORT} push --tls-verify=false ${OCI_DOMAIN}/gos/gos-$$svc:${OCI_TAG} ; \
	done

deploy-kafka:
	${K8S_BIN} create namespace kafka || true ;
	curl -L https://github.com/strimzi/strimzi-kafka-operator/releases/download/${STRIMZI_VERSION}/strimzi-cluster-operator-${STRIMZI_VERSION}.yaml | sed 's/namespace: .*/namespace: kafka/'   | ${K8S_BIN} apply -f - -n kafka
	${K8S_BIN} apply -f ./k8s/strimzi-kafka-${STRIMZI_VERSION}.yml -n kafka

deploy-istio:
	export ISTIO_VERSION=${ISTIO_VERSION} && curl -L https://istio.io/downloadIstio | sh - ; \
	cp istio-${ISTIO_VERSION}/bin/istioctl . ; \
	./istioctl manifest apply --set profile=demo ; \
	rm -r istio-${ISTIO_VERSION}

expose-kiali:
	./istioctl dashboard kiali

deploy: .ensure-yq
	for nms in ${NAMESPACES} ; do \
  		oc create namespace $$nms || true; \
  	done ;
	./genall.sh -pp ${PULL_POLICY} -d "${OCI_DOMAIN_IN_CLUSTER}" -t ${OCI_TAG} | ${K8S_BIN} apply -f - ;
ifeq ($(K8S_BIN),oc)
	${K8S_BIN} apply -f ./k8s/web-route.yml
endif

reset:
	${K8S_BIN} scale deployments --all -n gos-heroes --replicas=0;
	${K8S_BIN} scale deployments --all -n gos-arrows --replicas=0;
	${K8S_BIN} scale deployments --all -n gos-villains --replicas=0;
	${K8S_BIN} scale deployments --all -n gos-catapults --replicas=0;

simu-arrow-scaling-hero-native-vs-hotspot--native: reset
	${K8S_BIN} scale deployment hero-native -n gos-heroes --replicas=5; \
	${K8S_BIN} scale deployment arrow-j11hotspot -n gos-arrows --replicas=1; \
	${K8S_BIN} scale deployment villains-j11oj9 -n gos-villains --replicas=1;

simu-arrow-scaling-hero-native-vs-hotspot--hotspot: reset
	${K8S_BIN} scale deployment hero-j11hotspot -n gos-heroes --replicas=5; \
	${K8S_BIN} scale deployment arrow-j11hotspot -n gos-arrows --replicas=1; \
	${K8S_BIN} scale deployment villains-j11oj9 -n gos-villains --replicas=1;

simu-start-mixed:
	${K8S_BIN} delete pods -l type=game-object
	${K8S_BIN} scale deployment hero-native -n gos-heroes --replicas=2; \
	${K8S_BIN} scale deployment hero-j11hotspot -n gos-heroes  --replicas=2; \
	${K8S_BIN} scale deployment arrow-j11hotspot -n gos-arrows --replicas=1; \
	${K8S_BIN} scale deployment villains-j11oj9 -n gos-villains --replicas=1;

more-villains:
	./gentpl.sh villains-j11oj9 -pp ${PULL_POLICY} -d "${OCI_DOMAIN_IN_CLUSTER}" -t ${OCI_TAG} \
    	| yq w --tag '!!str' - "spec.template.spec.containers[0].env.(name==WAVES_SIZE).value" 10 \
    	| yq w --tag '!!str' - "spec.template.spec.containers[0].env.(name==WAVES_COUNT).value" 4 \
		| ${K8S_BIN} apply -n gos-villains -f -

much-more-villains:
	./gentpl.sh villains-j11oj9 -pp ${PULL_POLICY} -d "${OCI_DOMAIN_IN_CLUSTER}" -t ${OCI_TAG} \
    	| yq w --tag '!!str' - "spec.template.spec.containers[0].env.(name==WAVES_SIZE).value" 30 \
    	| yq w --tag '!!str' - "spec.template.spec.containers[0].env.(name==WAVES_COUNT).value" 4 \
		| ${K8S_BIN} apply -n gos-villains -f -

scale-service:
	${K8S_BIN} scale deployment $$svc --replicas=$(count)

expose:
	@echo "URL: http://localhost:8081/"
	${K8S_BIN} port-forward svc/web 8081:8081

undeploy:
	for nms in ${NAMESPACES} ; do \
		oc delete namespace $$nms || true; \
	done ;

restart-pods-go:
	${K8S_BIN} delete pods -l project=gos -l type=game-object

start-villains:
	export WAVES_DELAY=15 WAVES_SIZE=30 WAVES_COUNT=5 && java -jar ./villains/target/gos-villains-${VERSION}-runner.jar

start-catapult-vertx:
	export Y="150" && java -jar ./catapult-vertx/target/gos-catapult-vertx-${VERSION}-runner.jar

start-catapult-quarkus:
	export Y="350" && java -jar ./catapult-quarkus/target/gos-catapult-quarkus-${VERSION}-runner.jar

start-ned:
	export Y="150" SPEED="70" NAME="ned-stark" USE_BOW="true" && java -jar ./hero/target/gos-hero-${VERSION}-runner.jar

dev-web:
	cd web && mvn compile quarkus:dev

start-web:
	java -jar ./web/target/gos-web-${VERSION}-runner.jar

start-aria:
	export Y="350" SPEED="70" NAME="aria-stark" USE_BOW="true" && java -jar ./hero/target/gos-hero-${VERSION}-runner.jar

start-random-hero:
	unset X Y SPEED ID USE_BOW && java -jar ./hero/target/gos-hero-${VERSION}-runner.jar;

start-arrow:
	java -jar ./arrow/target/gos-arrow-${VERSION}-runner.jar

start-kafka:
	cd kafka; docker-compose up

start:
	make -j7 start-arrow start-villains start-catapult-vertx start-catapult-quarkus start-aria start-ned start-random-hero
