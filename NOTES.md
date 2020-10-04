

To push to a remote docker instance, something like this:

```
docker save couchbase-transactions-example:latest | gzip | DOCKER_HOST=ssh://ingenthr@dnix docker load
```

If you're running with a different config with local kubectl to remote, you may want to get the config from kind and tell kubectl to use a different config:
```
KUBECONFIG=/Users/ingenthr/.kube/config.dnix-kind
```


The canonical way to scale a deployment is (going from 1-2)…
```
kubectl scale --replicas=2 deployment/cbtransactionsexample
```

If you want to look at the logs for the deployment…
```
kubectl logs -f deployment/cbtransactionsexample
```
Assuming there is only one "replica", this would look at the logs for only that one pod.


If you want to look at the UI for a cluster…
```
kubectl port-forward service/cb-example 8091:8091
```

This will select one node in the service's deployment.


Prometheus K8S notes after installing the "stack"
kube-prometheus-stack has been installed. Check its status by running:
  kubectl --namespace default get pods -l "release=kube-prometheus-stack-1601616292"

Visit https://github.com/prometheus-operator/kube-prometheus for instructions on how to create & configure Alertmanager and Prometheus instances using the Operator.