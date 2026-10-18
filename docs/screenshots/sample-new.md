# Deployment guide

This guide explains how to deploy the service to the staging and production clusters. Run every step from the repository root.

## Requirements

- JDK 21
- Docker 24 or newer
- Access to the staging registry
- [x] A signed release tag

## Steps

1. Build the image with `./gradlew jibDockerBuild`.
2. Push the image to the registry.
3. Run the smoke tests against staging.
4. Apply the manifests with `kubectl apply -f k8s/`.

| Environment | Replicas | Region |
|---|---|---|
| staging | 2 | eu-west-1 |
| production | 6 | eu-central-1 |

```mermaid
flowchart LR
    Build --> Push --> Test --> Deploy
```

> [!WARNING]
> Deployments to production need an approval from the on-call engineer.
