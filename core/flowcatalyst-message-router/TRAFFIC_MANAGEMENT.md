# Traffic Management

Flow Catalyst Message Router supports optional **traffic management** for controlling which instances receive load balancer traffic based on their PRIMARY/STANDBY role.

## Overview

When standby mode is enabled, you typically have:
- One PRIMARY instance that processes messages
- One or more STANDBY instances waiting to take over

Without traffic management, both instances are registered with the load balancer and receive traffic. This means:
- API requests may hit STANDBY instances (which don't process messages)
- Resources are wasted routing traffic to inactive instances
- Monitoring is confusing (both instances appear healthy)

**Traffic management solves this** by automatically:
- Registering the PRIMARY instance with the load balancer
- Deregistering STANDBY instances from the load balancer
- Handling failover transitions automatically

## Architecture

### Strategy Pattern

Traffic management uses a pluggable strategy pattern:

```
TrafficManagementService
    └── selects strategy based on config
        ├── NoOpStrategy (default)
        ├── AwsAlbStrategy (AWS ECS/Fargate)
        ├── KubernetesStrategy (future)
        └── ReadinessProbeStrategy (future)
```

### Integration with Standby Mode

Traffic management is tightly integrated with standby mode:

```
StandbyService lifecycle:
    ├── onStartup: Acquire lock
    │   ├── SUCCESS → become PRIMARY → registerWithLoadBalancer()
    │   └── FAIL → become STANDBY → deregisterFromLoadBalancer()
    │
    ├── refreshLockTask (every 10s)
    │   ├── PRIMARY loses lock → deregisterFromLoadBalancer() → shutdown
    │   └── STANDBY acquires lock → registerWithLoadBalancer() → become PRIMARY
    │
    └── onShutdown: Release lock
        └── deregisterFromLoadBalancer()
```

## Supported Strategies

### 1. NoOp Strategy (Default)

**When to use**: Single instance deployments, or when you don't need load balancer integration.

**Behavior**: Does nothing - all instances remain registered with the load balancer.

**Configuration**:
```properties
traffic-management.enabled=false
# or
traffic-management.strategy=noop
```

### 2. AWS ALB Strategy

**When to use**: AWS ECS/Fargate deployments with Application Load Balancer.

**Behavior**:
- Auto-detects instance IP from ECS metadata
- Registers/deregisters targets from ALB target group using AWS SDK
- Handles AWS API retries and failures gracefully

**Requirements**:
- Running in AWS ECS/Fargate with ECS metadata endpoint
- IAM role with `elasticloadbalancing:RegisterTargets` and `DeregisterTargets` permissions
- Target group ARN configured

**Configuration**:
```properties
# Enable traffic management
traffic-management.enabled=true
traffic-management.strategy=aws-alb

# AWS ALB configuration
traffic-management.aws-alb.target-group-arn=arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/my-tg/abc123
traffic-management.aws-alb.region=us-east-1  # Optional, auto-detected from ECS metadata
traffic-management.aws-alb.port=8080  # Default
traffic-management.aws-alb.max-retries=3  # Default
traffic-management.aws-alb.retry-delay-ms=1000  # Default
```

**Environment variables**:
```bash
export TRAFFIC_MANAGEMENT_ENABLED=true
export TRAFFIC_MANAGEMENT_STRATEGY=aws-alb
export TRAFFIC_MANAGEMENT_AWS_ALB_TARGET_GROUP_ARN=arn:aws:...
```

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `traffic-management.enabled` | false | Enable traffic management |
| `traffic-management.strategy` | noop | Strategy to use: noop, aws-alb |
| `traffic-management.aws-alb.target-group-arn` | - | ARN of ALB target group (required for aws-alb) |
| `traffic-management.aws-alb.region` | auto-detect | AWS region (auto-detected from ECS metadata) |
| `traffic-management.aws-alb.instance-id` | auto-detect | Instance IP address (auto-detected from ECS metadata) |
| `traffic-management.aws-alb.port` | 8080 | Port to register with target group |
| `traffic-management.aws-alb.max-retries` | 3 | Max retries for AWS API calls |
| `traffic-management.aws-alb.retry-delay-ms` | 1000 | Initial retry delay (exponential backoff) |

## AWS Fargate Deployment

### IAM Role Policy

Create an IAM role for your ECS task with this policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "elasticloadbalancing:RegisterTargets",
        "elasticloadbalancing:DeregisterTargets"
      ],
      "Resource": "arn:aws:elasticloadbalancing:REGION:ACCOUNT:targetgroup/TARGET_GROUP_NAME/*"
    }
  ]
}
```

### ECS Task Definition

```json
{
  "family": "message-router",
  "taskRoleArn": "arn:aws:iam::ACCOUNT:role/MessageRouterTaskRole",
  "executionRoleArn": "arn:aws:iam::ACCOUNT:role/ecsTaskExecutionRole",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "containerDefinitions": [
    {
      "name": "message-router",
      "image": "flowcatalyst/flowcatalyst-message-router:latest",
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        }
      ],
      "environment": [
        {
          "name": "STANDBY_ENABLED",
          "value": "true"
        },
        {
          "name": "TRAFFIC_MANAGEMENT_ENABLED",
          "value": "true"
        },
        {
          "name": "TRAFFIC_MANAGEMENT_STRATEGY",
          "value": "aws-alb"
        },
        {
          "name": "TRAFFIC_MANAGEMENT_AWS_ALB_TARGET_GROUP_ARN",
          "value": "arn:aws:elasticloadbalancing:REGION:ACCOUNT:targetgroup/my-tg/abc123"
        },
        {
          "name": "REDIS_HOSTS",
          "value": "redis://redis.example.com:6379"
        }
      ]
    }
  ]
}
```

### ECS Service

Create an ECS service with:
- **Desired count**: 2 (one PRIMARY, one STANDBY)
- **Load balancer**: DO NOT attach the service to the load balancer
  - The instances will register themselves dynamically
  - Only the PRIMARY instance will be registered at any time
- **Health checks**: Use health check endpoint `/health/ready`

### ALB Target Group

Create a target group with:
- **Target type**: IP (for Fargate)
- **Protocol**: HTTP
- **Port**: 8080
- **Health check path**: `/health/ready`
- **Health check interval**: 30 seconds
- **Health check timeout**: 5 seconds
- **Healthy threshold**: 2
- **Unhealthy threshold**: 3

**Important**: Do NOT register the ECS service with the target group. The instances will register themselves.

## Monitoring

### Traffic Status Endpoint

Query the traffic management status:

```bash
curl http://localhost:8080/monitoring/traffic-status
```

**Response when disabled**:
```json
{
  "enabled": false,
  "message": "Traffic management not available"
}
```

**Response with NoOp strategy**:
```json
{
  "enabled": true,
  "strategyType": "noop",
  "registered": true,
  "targetInfo": "No traffic management - all instances receive traffic",
  "lastOperation": "none",
  "lastError": "none"
}
```

**Response with AWS ALB strategy**:
```json
{
  "enabled": true,
  "strategyType": "aws-alb",
  "registered": true,
  "targetInfo": "10.0.1.123:8080 -> arn:aws:elasticloadbalancing:...",
  "lastOperation": "register at 2025-01-18T10:30:45Z",
  "lastError": "none"
}
```

### Logs

Look for these log messages:

```
# Startup
[INFO] Traffic management enabled with strategy: aws-alb
[INFO] Using AWS ALB traffic strategy

# Registration
[INFO] Registering instance as active with load balancer
[INFO] Successfully registered 10.0.1.123:8080 with target group arn:...

# Deregistration
[INFO] Deregistering instance from load balancer
[INFO] Successfully deregistered 10.0.1.123:8080 from target group arn:...

# Errors
[SEVERE] Failed to register instance with load balancer: Connection timeout
[WARNING] Failed to deregister from load balancer: Rate limit exceeded - retrying
```

## Error Handling

### Graceful Degradation

Traffic management is designed to fail gracefully:
- Errors are logged but don't crash the application
- Standby mode continues to work even if traffic management fails
- Traffic routing may be incorrect, but message processing is unaffected

### Common Errors

**IAM Permission Denied**:
```
[SEVERE] Failed to register with ALB after 3 attempts: Access Denied
```
**Solution**: Add required IAM permissions to task role.

**Target Group Not Found**:
```
[SEVERE] Failed to register with ALB: Target group not found
```
**Solution**: Verify target group ARN is correct.

**Instance IP Detection Failed**:
```
[SEVERE] Failed to detect instance IP from ECS metadata
```
**Solution**: Manually configure `traffic-management.aws-alb.instance-id`.

## Failover Behavior

### Primary Crashes

```
Time 0s:    Primary (IP: 10.0.1.100) is registered with ALB
Time 10s:   Primary crashes
Time 10s:   ALB marks 10.0.1.100 as unhealthy (after failed health checks)
Time 20s:   Standby (IP: 10.0.1.101) acquires Redis lock
Time 20s:   Standby calls registerAsActive()
Time 20s:   AWS ALB registers 10.0.1.101 as target
Time 25s:   ALB marks 10.0.1.101 as healthy
Time 25s:   Traffic flows to new PRIMARY (10.0.1.101)

Total failover: ~15-25 seconds
```

### Primary Graceful Shutdown

```
Time 0s:    Primary (IP: 10.0.1.100) receives SIGTERM
Time 0s:    Primary calls deregisterFromLoadBalancer()
Time 1s:    AWS ALB deregisters 10.0.1.100
Time 1s:    ALB stops routing new traffic to 10.0.1.100
Time 1s:    Primary releases Redis lock
Time 2s:    Standby (IP: 10.0.1.101) acquires Redis lock
Time 2s:    Standby calls registerAsActive()
Time 2s:    AWS ALB registers 10.0.1.101 as target
Time 7s:    ALB marks 10.0.1.101 as healthy
Time 7s:    Traffic flows to new PRIMARY (10.0.1.101)

Total failover: ~5-10 seconds (faster than crash scenario)
```

## Performance Impact

### Overhead
- **Registration/deregistration**: 1-2 AWS API calls per role change
- **Typical latency**: 100-300ms per API call
- **Network bandwidth**: Minimal (<1 KB per operation)
- **CPU impact**: Negligible

### Timing
- **Registration time**: 1-5 seconds (AWS API + health check)
- **Deregistration time**: 1-3 seconds (AWS API)
- **Health check interval**: Controlled by ALB settings (typically 30s)

## Best Practices

1. **Always Use with Standby Mode**
   - Traffic management is designed for standby mode
   - Don't enable without `standby.enabled=true`

2. **Monitor Both Endpoints**
   - `/monitoring/standby-status` - Check PRIMARY/STANDBY role
   - `/monitoring/traffic-status` - Check load balancer registration

3. **Configure ALB Health Checks**
   - Use `/health/ready` endpoint
   - Set reasonable intervals (30s recommended)
   - Allow for lock acquisition time (30s TTL)

4. **Test Failover**
   - Kill PRIMARY instance and verify STANDBY takes over
   - Check ALB target health during failover
   - Verify traffic flows to new PRIMARY

5. **Use IAM Roles, Not Credentials**
   - Never hardcode AWS credentials
   - Use ECS task role for Fargate deployments
   - Grant minimum required permissions

6. **Handle Transient Failures**
   - AWS API may occasionally fail
   - Default retry settings (3 retries, exponential backoff) handle most cases
   - Monitor for repeated failures

## Troubleshooting

### Both instances registered with ALB

**Symptom**: Both PRIMARY and STANDBY instances show as healthy in target group.

**Diagnosis**:
```bash
# Check if traffic management is enabled
curl http://primary:8080/monitoring/traffic-status
curl http://standby:8080/monitoring/traffic-status
```

**Solution**:
- Verify `traffic-management.enabled=true`
- Check strategy is set to `aws-alb`
- Review IAM permissions

### No instances registered with ALB

**Symptom**: Target group shows no healthy targets.

**Diagnosis**:
```bash
# Check standby status
curl http://instance:8080/monitoring/standby-status

# Check traffic status
curl http://instance:8080/monitoring/traffic-status
```

**Solution**:
- Check if any instance has PRIMARY role
- Verify Redis is available
- Review registration errors in logs

### Registration succeeds but health check fails

**Symptom**: Instance registers with ALB but never becomes healthy.

**Diagnosis**:
```bash
# Test health endpoint directly
curl http://instance:8080/health/ready
```

**Solution**:
- Verify application is actually healthy
- Check ALB health check path matches `/health/ready`
- Ensure security groups allow ALB → instance traffic on port 8080

## Related Documentation

- [STANDBY.md](STANDBY.md) - Hot standby mode documentation
- [STANDBY_IMPLEMENTATION.md](STANDBY_IMPLEMENTATION.md) - Implementation details
- [AUTHENTICATION.md](AUTHENTICATION.md) - Optional authentication feature
