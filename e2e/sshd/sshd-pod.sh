#!/bin/bash
# The throw-away sshd of SshConnectorE2E as a Pod on W206, behind a NodePort, so that ssh-connector reaches it as
# another computer: its startup guard refuses the user's own computer (tasks/todo.md, "ssh-connector's startup guard").
# Runs where kubectl reaches W206. Never a real cluster, and dummy keys only.
#
#   SSHD_IMAGE=<image> sshd-pod.sh start <name>    stdin: a tar of host_key, host_key.pub and authorized_keys;
#                                                  prints NODEPORT=<port> once the Pod is Ready
#   sshd-pod.sh stop <name>                        deletes the Pod and its keys Secret
#   sshd-pod.sh exec <name>                        stdin: a shell command, run in the Pod; its output is printed as is
#   sshd-pod.sh teardown                           deletes the whole namespace and shows that it is gone
set -euo pipefail
NS=${SSHD_NAMESPACE:-chat77-sshd-e2e}
verb=${1:?start, stop, exec or teardown}
shift || true
case "$verb" in
  start)
    name=${1:?name}
    image=${SSHD_IMAGE:?SSHD_IMAGE}
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    tar -xf - -C "$tmp"
    kubectl get namespace "$NS" >/dev/null 2>&1 || kubectl create namespace "$NS" >/dev/null
    kubectl -n "$NS" create secret generic "$name-keys" --from-file=host_key="$tmp/host_key" \
      --from-file=authorized_keys="$tmp/authorized_keys" >/dev/null
    kubectl -n "$NS" apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $name
  labels:
    app: ack-sshd-e2e
spec:
  restartPolicy: Never
  containers:
  - name: sshd
    image: $image
    imagePullPolicy: IfNotPresent
    ports:
    - containerPort: 2222
    volumeMounts:
    - name: keys
      mountPath: /keys
      readOnly: true
  volumes:
  - name: keys
    secret:
      secretName: $name-keys
      defaultMode: 0400
EOF
    if ! kubectl -n "$NS" get service ack-sshd-e2e >/dev/null 2>&1; then
      kubectl -n "$NS" apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Service
metadata:
  name: ack-sshd-e2e
spec:
  type: NodePort
  selector:
    app: ack-sshd-e2e
  ports:
  - port: 2222
    targetPort: 2222
EOF
    fi
    kubectl -n "$NS" wait --for=condition=Ready "pod/$name" --timeout=180s >/dev/null
    echo "NODEPORT=$(kubectl -n "$NS" get service ack-sshd-e2e -o jsonpath='{.spec.ports[0].nodePort}')"
    ;;
  stop)
    name=${1:?name}
    kubectl -n "$NS" delete pod "$name" --wait=true --ignore-not-found >/dev/null
    kubectl -n "$NS" delete secret "$name-keys" --ignore-not-found >/dev/null
    ;;
  exec)
    name=${1:?name}
    cmd=$(cat)
    kubectl -n "$NS" exec "$name" -- sh -c "$cmd"
    ;;
  teardown)
    kubectl delete namespace "$NS" --wait=true --ignore-not-found >/dev/null
    if kubectl get namespace "$NS" >/dev/null 2>&1; then echo "namespace $NS still exists"; exit 1; fi
    echo "namespace $NS is gone"
    ;;
  *)
    echo "unknown verb $verb" >&2
    exit 64
    ;;
esac
