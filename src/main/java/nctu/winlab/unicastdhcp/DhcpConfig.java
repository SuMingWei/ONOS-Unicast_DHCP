package nctu.winlab.unicastdhcp;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;

public class DhcpConfig extends Config<ApplicationId> {
    public static final String SERVERLOCATION = "serverLocation";

    @Override
    public boolean isValid() {
        return hasOnlyFields(SERVERLOCATION);
    }

    public String serverLocation() {
        return get(SERVERLOCATION, null);
    }
}
