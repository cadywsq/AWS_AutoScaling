import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import org.ini4j.Ini;
import org.ini4j.Profile;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * @author Siqi Wang siqiw1 on 2/4/16.
 */
public class AwsScalingHelper {
    protected static final String ACCESS_KEY = "aws_access_key_id";
    protected static final String SECRET_KEY = "aws_secret_access_key";
    protected static String andrewId = "";
    protected static String password = "";

    protected static Instance launchInstance(AmazonEC2Client ec2, String imageID, String securityGroup)
            throws IOException, InterruptedException {

        //Create Instance Request
        RunInstancesRequest runInstancesRequest = new RunInstancesRequest();
        //Set availability zone of the Instance
        Placement region = new Placement("us-east-1a");
        runInstancesRequest.setPlacement(region);
        //Configure Instance Request
        runInstancesRequest
                .withImageId(imageID)
                .withInstanceType("m3.medium")
                .withMinCount(1)
                .withMaxCount(1)
                .withKeyName("Project0")
                .withSecurityGroups(securityGroup);

        //Launch Instance
        RunInstancesResult runInstancesResult = ec2.runInstances(runInstancesRequest);
        //Return the Object Reference of the Instance just Launched
        Instance instance = runInstancesResult.getReservation().getInstances().get(0);
        //Add tag Project:2.1 to the new instance
        CreateTagsRequest tagsRequest = createTagRequest(instance);
        ec2.createTags(tagsRequest);

        //Check instance state and update instance object reference.
        DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest();
        InstanceState instanceState = instance.getState();

        while (instanceState.getCode() != 16) {
            TimeUnit.SECONDS.sleep(1);
            describeInstancesRequest.withInstanceIds(instance.getInstanceId());
            DescribeInstancesResult describeInstanceResult = ec2.describeInstances(describeInstancesRequest);
            instance = describeInstanceResult.getReservations().get(0).getInstances().get(0);
            instanceState = instance.getState();
        }
        return instance;
    }

    protected static CreateTagsRequest createTagRequest(Instance instance) {
        Tag tag = new Tag("Project", "2.1");
        CreateTagsRequest tagsRequest = new CreateTagsRequest();
        tagsRequest.withResources(instance.getInstanceId())
                .withTags(tag);
        return tagsRequest;
    }

    /**
     * Set up security group which opens to all ports.
     *
     * @param amazonEC2Client the EC2 client
     */
    protected static String createSecurityGroup(AmazonEC2Client amazonEC2Client, String securityGroup) {
        CreateSecurityGroupRequest securityGroupRequest = new CreateSecurityGroupRequest();
        securityGroupRequest.withGroupName(securityGroup)
                .withDescription("Horizontal scaling security group");
        CreateSecurityGroupResult result = amazonEC2Client.createSecurityGroup(securityGroupRequest);

        //Add rule of inbound: open to all ports.
        IpPermission ipPermission = new IpPermission();
        ipPermission.withIpRanges("0.0.0.0/0")
                .withIpProtocol("-1")
                .withFromPort(0)
                .withToPort(65535);
        AuthorizeSecurityGroupIngressRequest authorizeSecurityGroupIngressRequest =
                new AuthorizeSecurityGroupIngressRequest();
        authorizeSecurityGroupIngressRequest.withGroupName(securityGroup)
                .withIpPermissions(ipPermission);
        amazonEC2Client.authorizeSecurityGroupIngress(authorizeSecurityGroupIngressRequest);
        return result.getGroupId();
    }

    /**
     * Connect to the URL by submitting http request
     *
     * @param link linkage of the URL
     */
    protected static void getConnection(String link) {
        while (true) {
            try {
                URL url = new URL(link);
                HttpURLConnection connection;
                int responseCode = 0;
                while (responseCode != 200) {
                    TimeUnit.SECONDS.sleep(5);
                    connection = (HttpURLConnection) url.openConnection();
                    responseCode = connection.getResponseCode();
                }
                break;
            } catch (IOException | InterruptedException e) {
                System.out.println("Wait...");;
            }
        }
    }

    /**
     * Start horizontal scaling test.
     *
     * @param lg the load generator instance
     * @param dc the first data center instance
     */
    protected static void startTest(Instance lg, Instance dc) {
        String credentialUrl = submitCredentialUrl(lg);
        getConnection(credentialUrl);
        String startTestUrl = startTestUrl(lg, dc);
        getConnection(startTestUrl);
    }

    protected static String getDNS(Instance instance) {
        return instance.getPublicDnsName();
    }

    protected static String submitCredentialUrl(Instance lg) {
        return "http://" + getDNS(lg) + "/password?passwd=" + password + "&andrewId=" +
                andrewId;
    }

    private static String startTestUrl(Instance lg, Instance dc) {
        return "http://" + getDNS(lg) + "/test/horizontal?dns=" + getDNS(dc);
    }

    protected static void terminateInstance(AmazonEC2Client ec2, List<String> instanceIds) {
        TerminateInstancesRequest tir = new TerminateInstancesRequest(instanceIds);
        ec2.terminateInstances(tir);
    }

    /**
     * Submit the http request as an input stream.
     *
     * @param link the URL of the http request
     * @return the input stream from the URL
     */
    protected static InputStream getInputStream(String link) {
        while (true) {
            try {
                URL url = new URL(link);
                InputStream input = url.openStream();
                while (input == null) {
                    TimeUnit.SECONDS.sleep(5);
                    input = url.openStream();
                }
                return input;
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

        /**
         * Read cumulative RPS from system log.
         *
         * @param lg the load generator instance
         * @return current cumulative RPS
         * @throws IOException
         */

    static double monitorRPS(Instance lg) throws IOException {
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        String logName = getLogName(lg);
        double cumulativeRPS = 0;
        String logLink = String.format("http://%s/log?name=%s", getDNS(lg), logName);
        Ini ini = new Ini(getInputStream(logLink));
        for (String sectionName : ini.keySet()) {
            if (sectionName.startsWith("Minute")) {
                cumulativeRPS = 0;
                Profile.Section section = ini.get(sectionName);

                for (String optionKey : section.keySet()) {
                    cumulativeRPS += Double.parseDouble(section.get(optionKey));
                }
            }
        }
        return cumulativeRPS;
    }

    protected static String getLogName(Instance lg) throws IOException {
        String logLink = String.format("http://%s/log", getDNS(lg));
        try (Scanner reader = new Scanner(new InputStreamReader(getInputStream(logLink)))) {
            String[] line = reader.nextLine().split("log\\?name=");
            String log = line[1].substring(0, 22);
            return log;
        }
    }
}
