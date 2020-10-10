

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



Setting up prometheus/grafana through helm

1. get helm 3 (homebrew has it)
2. add the prometheus-community helm repo prometheus-community	https://prometheus-community.github.io/helm-charts
3. for a single stack of everything including grafana `helm install prometheus-community/kube-prometheus-stack --name-template cbtxnex`

the default username/password is admin/prom-operator and you would need to port-forward with something like:
`$ kubectl port-forward service/cbtxnex-grafana 9080:80`

Prometheus K8S notes after installing the "stack"
kube-prometheus-stack has been installed. Check its status by running:
  kubectl --namespace default get pods -l "release=kube-prometheus-stack-1601616292"

Visit https://github.com/prometheus-operator/kube-prometheus for instructions on how to create & configure Alertmanager and Prometheus instances using the Operator.

To flush the prometheus data, enable web API management, then port forward, then:
$ curl -X POST -g 'http://localhost:9090/api/v1/admin/tsdb/delete_series?match[]={__name__=~".+"}'

