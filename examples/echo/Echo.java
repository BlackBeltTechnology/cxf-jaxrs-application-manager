package hu.blackbelt.jaxrs.poc;

import org.osgi.service.component.annotations.Component;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.util.Map;
import java.util.TreeMap;

@Component(immediate = true, property = "appName=poc", service = Echo.class)
@Path("/")
public class Echo {

    @GET
    @Path("/echo")
    @Produces({ "application/json; charset=UTF-8" })
    public Response echo(@QueryParam("name") final String name) {
        final Map<String, Object> response = new TreeMap<>();
        response.put("name", name);
        return Response.ok().entity(response).build();
    }
}
