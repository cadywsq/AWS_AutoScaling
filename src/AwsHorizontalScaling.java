import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.Instance;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * @author Siqi Wang siqiw1 on 2/2/16.
 */
public class AwsHorizontalScaling extends AwsScalingHelper {

    protected static final String SECURITY_GROUP = "CloudComputingScalingSecurityGroup";

    public static void main(String[] args) throws IOException {
        andrewId = args[0].trim();
        password = args[1].trim();
        //Load the Properties File with AWS Credentials
        Properties properties = new Properties();
        properties.load(AwsHorizontalScaling.class.getResourceAsStream("./AwsCredentials.properties"));
        BasicAWSCredentials basicAWSCredentials = new BasicAWSCredentials(properties.getProperty(ACCESS_KEY), properties.getProperty(SECRET_KEY));
        //Create an Amazon EC2 Client
        AmazonEC2Client ec2 = new AmazonEC2Client(basicAWSCredentials);
        //Create security group "ScalingSecurityGroup"
        createSecurityGroup(ec2,SECURITY_GROUP);

        Instance lg = null;
        List<String> listOfDataCenter = new ArrayList<>();
        try {
            //launch load generator
            lg = launchInstance(ec2, "ami-8ac4e9e0",SECURITY_GROUP);
            System.out.println("instance" + lg.getInstanceId() + " is launched");
            //launch first data center and add to array list
            Instance dc = launchInstance(ec2, "ami-349fbb5e",SECURITY_GROUP);
            listOfDataCenter.add(dc.getInstanceId());
            System.out.println("instance" + dc.getInstanceId() + " is launched");

            //start the test process
            startTest(lg, dc);

            //If cumulative RPS is less than 4000, launch new data center instance, submit to load balancer.
            //Wait for 1 minute to refresh test log for RPS.
            while (monitorRPS(lg) < 4000) {
                Instance newDc = launchInstance(ec2, "ami-349fbb5e",SECURITY_GROUP);
                System.out.println("instance" + newDc.getInstanceId() + " is launched");
                getConnection(addDcUrl(lg, newDc));
                TimeUnit.SECONDS.sleep(60);
            }
        } catch (InterruptedException e) {
            monitorRPS(lg);
        }
        terminateInstance(ec2, listOfDataCenter);
    }

    private static String addDcUrl(Instance lg, Instance dc) {
        return "http://" + getDNS(lg) + "/test/horizontal/add?dns=" + getDNS(dc);
    }

}
