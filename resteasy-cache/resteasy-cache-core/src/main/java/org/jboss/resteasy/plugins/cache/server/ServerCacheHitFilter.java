package org.jboss.resteasy.plugins.cache.server;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class ServerCacheHitFilter implements ContainerRequestFilter
{
   protected ServerCache cache;
   public static final String DO_NOT_CACHE_RESPONSE = "DO NOT CACHE RESPONSE";

   public ServerCacheHitFilter(final ServerCache cache)
   {
      this.cache = cache;
   }

   @Context
   protected Request validation;

   @Override
   public void filter(ContainerRequestContext request) throws IOException
   {
      String key = request.getUriInfo().getRequestUri().toString();
      if (request.getMethod().equalsIgnoreCase("GET"))
      {
         handleGET(request, key);
      }
      else if (!request.getMethod().equalsIgnoreCase("HEAD"))
      {
         cache.remove(key);
      }
   }

   private void handleGET(ContainerRequestContext request, String key)
   {
      ServerCache.Entry entry = null;
      List<MediaType> acceptableMediaTypes = request.getAcceptableMediaTypes();
      if (acceptableMediaTypes != null && acceptableMediaTypes.size() > 0)
      {
         // only see if most desired is cached.
         entry = cache.get(key, acceptableMediaTypes.get(0), request.getHeaders());
      }
      else
      {
         entry = cache.get(key, MediaType.WILDCARD_TYPE, request.getHeaders());
      }
      if (entry != null)
      {
         if (entry.isExpired())
         {
            cache.remove(key);
            return;
         }
         else
         {
            // validation if client sent
            Response.ResponseBuilder builder = validation.evaluatePreconditions(new EntityTag(entry.getEtag()));
            CacheControl cc = new CacheControl();
            cc.setMaxAge(entry.getExpirationInSeconds());
            if (builder != null)
            {
               request.abortWith(builder.cacheControl(cc).build());
               return;
            }

            builder = Response.ok();
            builder.entity(entry.getCached());

            for (Map.Entry<String, List<Object>> header : entry.getHeaders().entrySet())
            {
               for (Object val : header.getValue())
               {
                  builder.header(header.getKey(), val);
               }
            }
            builder.cacheControl(cc);
            request.setProperty(DO_NOT_CACHE_RESPONSE, true);
            request.abortWith(builder.build());
         }
      }
      else
      {
      }
   }
}
