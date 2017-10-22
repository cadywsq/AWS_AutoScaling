import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.CreateAutoScalingGroupRequest;
import com.amazonaws.services.autoscaling.model.CreateLaunchConfigurationRequest;
import com.amazonaws.services.autoscaling.model.DeleteAutoScalingGroupRequest;
import com.amazonaws.services.autoscaling.model.DeleteLaunchConfigurationRequest;
import com.amazonaws.services.autoscaling.model.InstanceMonitoring;
import com.amazonaws.services.autoscaling.model.PutScalingPolicyRequest;
import com.amazonaws.services.autoscaling.model.PutScalingPolicyResult;
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClient;
import com.amazonaws.services.cloudwatch.model.ComparisonOperator;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.PutMetricAlarmRequest;
import com.amazonaws.services.cloudwatch.model.StandardUnit;
import com.amazonaws.services.cloudwatch.model.Statistic;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DeleteSecurityGroupRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient;
import com.amazonaws.services.elasticloadbalancing.model.ConfigureHealthCheckRequest;
import com.amazonaws.services.elasticloadbalancing.model.ConfigureHealthCheckResult;
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerResult;
import com.amazonaws.services.elasticloadbalancing.model.DeleteLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.HealthCheck;
import com.amazonaws.services.elasticloadbalancing.model.Listener;
import com.amazonaws.services.elasticloadbalancing.model.Tag;
import org.ini4j.Ini;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * @author Siqi Wang siqiw1 on 2/4/16.
 */
public class AwsAutoScaling extends AwsScalingHelper {

    private static final String SECURITY_GROUP_1 = "AutoScaling1";
    private static final String SECURITY_GROUP_2 = "AutoScaling2";
    private static final String LOAD_GENERATOR_ID = "ami-8ac4e9e0";
    private static final String DATA_CENTER_ID = "ami-349fbb5e";
    private static Properties properties = new Properties();

    private static AmazonEC2Client ec2Client;
    private static AmazonElasticLoadBalancingClient elbClient;
    private static AmazonAutoScalingClient autoScalingClient;
    private static AmazonCloudWatchClient cloudWatchClient;

    public static void main(String[] args) throws IOException, InterruptedException {
        andrewId = args[0].trim();
        password = args[1].trim();
        //Load the Properties File with AWS Credentials
        properties.load(AwsAutoScaling.class.getResourceAsStream("./AwsCredentials.properties"));
        BasicAWSCredentials basicAWSCredentials = new BasicAWSCredentials(properties.getProperty(ACCESS_KEY), properties.getProperty(SECRET_KEY));
        //Create an Amazon EC2 Client
        ec2Client = new AmazonEC2Client(basicAWSCredentials);
        //Create an ELB client
        elbClient = new AmazonElasticLoadBalancingClient(basicAWSCredentials);
        //Create an ASG client
        autoScalingClient = new AmazonAutoScalingClient(basicAWSCredentials);
        //Create a Cloud Watch client
        cloudWatchClient = new AmazonCloudWatchClient(basicAWSCredentials);

        //Create security groups
        createSecurityGroup(ec2Client, SECURITY_GROUP_1);
        String sg2Id = createSecurityGroup(ec2Client, SECURITY_GROUP_2);
        //Create load generator
        Instance lg = launchInstance(ec2Client, LOAD_GENERATOR_ID, SECURITY_GROUP_1);
        System.out.println("Instance " + lg.getInstanceId() + " is launched");
        //Create ELB
        CreateLoadBalancerResult elbResult = launchElb(sg2Id);
        System.out.println("ELB " + elbResult.getDNSName() + " is launched");
        //Create and set up Health Check
        launchHealthCheck(lg);
        //Setup launch configuration
        setLaunchConfiguration(sg2Id);
        //Create ASG
        launchAsg();
        //Setup scale up policy
        setupScaleOutAlarm();
        //Setup scale down policy
        setupScaleInAlarm();
        //submit password
        TimeUnit.MINUTES.sleep(1);
        getConnection(submitCredentialUrl(lg));
        System.out.println("Credential URL: " + submitCredentialUrl(lg));
        //ELB warmup
        getConnection(getWarmupUrl(lg, elbResult.getDNSName()));
        System.out.println("Warmup URL: " + getWarmupUrl(lg, elbResult.getDNSName()));
        TimeUnit.MINUTES.sleep(16);

        //Start the junior test
        getConnection(String.format("http://%s/junior?dns=%s", getDNS(lg), elbResult.getDNSName()));
        System.out.println("Junior test is launched");

        //Read log to decide termination
        String logName = getLogName(lg);
        String logLink = String.format("http://%s/log?name=%s", getDNS(lg), logName);
        Ini ini;
        while (true) {
            TimeUnit.MINUTES.sleep(1);
            ini = new Ini(getInputStream(logLink));
            for (String sectionName : ini.keySet()) {
                if (sectionName.equals("Test End")) {
                    terminateLb();
                    terminateAsg();
                    terminateLaunchConfig();
                    deleteSecurityGroup();
                    break;
                }
            }
        }
    }

    private static CreateLoadBalancerResult launchElb(String sg2Id) throws InterruptedException {
        //create load balancer
        CreateLoadBalancerRequest lbRequest = new CreateLoadBalancerRequest();
        Tag tag = new Tag();
        tag.setKey("Project");
        tag.setValue("2.1");
        lbRequest.withLoadBalancerName("loadbalancer")
                .withListeners(new Listener("HTTP", 80, 80))
                .withSecurityGroups(sg2Id)
                .withAvailabilityZones("us-east-1a")
                .withTags(tag);
        System.out.println("Doing LB request: " + lbRequest);

        CreateLoadBalancerResult lbResult = elbClient.createLoadBalancer(lbRequest);
        while (lbResult.getDNSName() == null) {
            TimeUnit.SECONDS.sleep(3);
        }
        System.out.println("Created load balancer " + lbResult);
        return lbResult;
    }


    private static void launchHealthCheck(Instance lg) {
        HealthCheck healthCheck = new HealthCheck();
        String hcUrl = String.format("HTTP:80/heartbeat?lg=%s", getDNS(lg));
        healthCheck.withHealthyThreshold(2)
                .withUnhealthyThreshold(10)
                .withInterval(30)
                .withTimeout(10)
                .withTarget(hcUrl);
        ConfigureHealthCheckRequest hcRequest = new ConfigureHealthCheckRequest("loadbalancer", healthCheck);
        System.out.println("Health check launching for " + hcRequest);
        ConfigureHealthCheckResult hcResult = elbClient.configureHealthCheck(hcRequest);
        System.out.println("Health check result: " + hcResult);
    }

    private static String getWarmupUrl(Instance lg, String elbDns) {
        return String.format("http://%s/warmup?dns=%s", getDNS(lg), elbDns);
    }

    private static void setLaunchConfiguration(String sg2Id) {
        CreateLaunchConfigurationRequest lcRequest = new CreateLaunchConfigurationRequest();

        lcRequest.withLaunchConfigurationName("ASGConfig")
                .withImageId(DATA_CENTER_ID)
                .withInstanceType("m3.medium")
                .withSecurityGroups(sg2Id);

        InstanceMonitoring monitoring = new InstanceMonitoring();
        monitoring.setEnabled(Boolean.TRUE);
        lcRequest.setInstanceMonitoring(monitoring);

        System.out.println("Setting launch configuration request: " + lcRequest);
        autoScalingClient.createLaunchConfiguration(lcRequest);
    }

    private static void launchAsg() {
        CreateAutoScalingGroupRequest asgRequest = new CreateAutoScalingGroupRequest();
        com.amazonaws.services.autoscaling.model.Tag tag = new com.amazonaws.services.autoscaling.model.Tag();
        tag.setKey("Project");
        tag.setValue("2.1");

        asgRequest.withAutoScalingGroupName("ASG")
                .withAvailabilityZones("us-east-1a")
                .withLaunchConfigurationName("ASGConfig")
                .withLoadBalancerNames("loadbalancer")
                .withTags(tag)
                .withMaxSize(5)
                .withMinSize(1)
                .withDesiredCapacity(1)
                .withHealthCheckType("ELB")
                .withHealthCheckGracePeriod(240);
        //      .withDefaultCooldown(60);

        System.out.println("Creating ASG: " + asgRequest);
        autoScalingClient.createAutoScalingGroup(asgRequest);
    }

    private static String scalingOutPolicy() {
        PutScalingPolicyRequest scalingPolicyRequest = new PutScalingPolicyRequest();
        scalingPolicyRequest.withAutoScalingGroupName("ASG")
                .withPolicyName("ScaleUp")
                .withScalingAdjustment(1)
                .withAdjustmentType("ChangeInCapacity");
        System.out.println("Scale out policy: " + scalingPolicyRequest);

        PutScalingPolicyResult result = autoScalingClient.putScalingPolicy(scalingPolicyRequest);
        System.out.println("Scale out policy: " + result);
        return result.getPolicyARN(); // The policy ARN is needed in the next step.
    }

    private static String scalingInPolicy() {
        PutScalingPolicyRequest scalingPolicyRequest = new PutScalingPolicyRequest();
        scalingPolicyRequest.withAutoScalingGroupName("ASG")
                .withPolicyName("ScaleDown")
                .withScalingAdjustment(-1)
                .withAdjustmentType("ChangeInCapacity");
        System.out.println("Scale in policy: " + scalingPolicyRequest);

        PutScalingPolicyResult result = autoScalingClient.putScalingPolicy(scalingPolicyRequest);
        System.out.println("Scale in policy: " + result);
        return result.getPolicyARN(); // The policy ARN is needed in the next step.
    }

    private static void setupScaleOutAlarm() {
        String scaleUpArn = scalingOutPolicy();

        Dimension dimension = new Dimension();
        dimension.setName("AutoScalingGroupName");
        dimension.setValue("ASG");

        PutMetricAlarmRequest outRequest = new PutMetricAlarmRequest();
        outRequest.withAlarmName("ScaleUpAlarm")
                .withActionsEnabled(true)
                .withDimensions(dimension)
                .withMetricName("CPUUtilization")
                .withNamespace("AWS/EC2")
                .withComparisonOperator(ComparisonOperator.GreaterThanOrEqualToThreshold)
                .withThreshold(60d)
                .withPeriod(60)
                .withEvaluationPeriods(1)
                .withStatistic(Statistic.Average)
                .withUnit(StandardUnit.Percent)
                .withAlarmActions(scaleUpArn);

        System.out.println("Launching scale out alarm " + outRequest);
        cloudWatchClient.putMetricAlarm(outRequest);
    }

    private static void setupScaleInAlarm() {
        String scaleDownArn = scalingInPolicy();

        Dimension dimension = new Dimension();
        dimension.setName("AutoScalingGroupName");
        dimension.setValue("ASG");

        PutMetricAlarmRequest inRequest = new PutMetricAlarmRequest();
        inRequest.withAlarmName("ScaleDownAlarm")
                .withActionsEnabled(true)
                .withDimensions(dimension)
                .withMetricName("CPUUtilization")
                .withNamespace("AWS/EC2")
                .withComparisonOperator(ComparisonOperator.LessThanOrEqualToThreshold)
                .withThreshold(20d)
                .withPeriod(60)
                .withEvaluationPeriods(5)
                .withStatistic(Statistic.Average)
                .withUnit(StandardUnit.Percent)
                .withAlarmActions(scaleDownArn);

        System.out.println("Launching scale in alarm: " + inRequest);
        cloudWatchClient.putMetricAlarm(inRequest);
    }

    private static void terminateLb() {
        DeleteLoadBalancerRequest deleteElb = new DeleteLoadBalancerRequest("loadbalancer");
        elbClient.deleteLoadBalancer(deleteElb);
    }

    private static void terminateAsg() {
        while (true) {
            try {
                UpdateAutoScalingGroupRequest updateRequest = new UpdateAutoScalingGroupRequest();
                updateRequest.withAutoScalingGroupName("ASG")
                        .withMinSize(0)
                        .withMaxSize(0);
                autoScalingClient.updateAutoScalingGroup(updateRequest);

                DeleteAutoScalingGroupRequest deleteAsg = new DeleteAutoScalingGroupRequest();
                deleteAsg.withAutoScalingGroupName("ASG");
                autoScalingClient.deleteAutoScalingGroup(deleteAsg);
                return;
            } catch (Exception e) {
                System.out.println("Wait...");
            }
        }
    }

    private static void terminateLaunchConfig() {
        DeleteLaunchConfigurationRequest deleteLc = new DeleteLaunchConfigurationRequest();
        autoScalingClient.deleteLaunchConfiguration(deleteLc);
    }


    private static void deleteSecurityGroup() {
        deleteSingleSecurityGroup(SECURITY_GROUP_1);
        deleteSingleSecurityGroup(SECURITY_GROUP_2);

    }

    private static void deleteSingleSecurityGroup(String securityGroup2) {
        while (true) {
            try {
                DeleteSecurityGroupRequest deleteSg2 = new DeleteSecurityGroupRequest(securityGroup2);
                ec2Client.deleteSecurityGroup(deleteSg2);
                break;
            } catch (Exception e) {
                System.out.println("Wait...");
            }
        }
    }
}
