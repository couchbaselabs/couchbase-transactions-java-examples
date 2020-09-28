

To push to a remote docker instance, something like this:

```
docker save couchbase-transactions-example:latest | gzip | DOCKER_HOST=ssh://ingenthr@dnix docker load
```

If you're running with a different config with local kubectl to remote, you may want to get the config from kind and tell kubectl to use a different config:
```
KUBECONFIG=/Users/ingenthr/.kube/config.dnix-kind
```