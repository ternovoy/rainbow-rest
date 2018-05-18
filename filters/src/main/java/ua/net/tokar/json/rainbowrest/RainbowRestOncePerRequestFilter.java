package ua.net.tokar.json.rainbowrest;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.*;

abstract class RainbowRestOncePerRequestFilter implements Filter {

    private static final int DEFAULT_NUMBER_OF_THREADS = 10;
    private static final int DEFAULT_EXECUTION_TIMEOUT_SECONDS = 10;
    private static final String ALREADY_FILTERED_SUFFIX = ".FILTERED";
    private static final String DEFAULT_NUMBER_OF_THREADS_PARAM_NAME = "numberOfThreads";
    private static final String DEFAULT_EXECUTION_TIMEOUT_SECONDS_PARAM_NAME = "executionTimeoutSeconds";

    private ExecutorService executorService;
    private int executionTimeoutSeconds;

    public RainbowRestOncePerRequestFilter() {
        this.executorService = Executors.newFixedThreadPool( DEFAULT_NUMBER_OF_THREADS );
        this.executionTimeoutSeconds = DEFAULT_EXECUTION_TIMEOUT_SECONDS;
    }

    public RainbowRestOncePerRequestFilter(
            int numberOfThreads,
            int executionTimeoutSeconds
    ) {
        this.executorService = Executors.newFixedThreadPool( numberOfThreads );
        this.executionTimeoutSeconds = executionTimeoutSeconds;
    }

    @Override
    public void init( FilterConfig filterConfig ) throws ServletException {
        String numberOfThreadsOverride =
                filterConfig.getInitParameter( DEFAULT_NUMBER_OF_THREADS_PARAM_NAME );
        if ( StringUtils.isNotEmpty( numberOfThreadsOverride ) ) {
            executorService.shutdownNow();

            executorService = Executors
                    .newFixedThreadPool( Integer.valueOf( numberOfThreadsOverride ) );
        }
        String executionTimeoutSecondsOverride =
                filterConfig.getInitParameter( DEFAULT_EXECUTION_TIMEOUT_SECONDS_PARAM_NAME );
        if ( StringUtils.isNotEmpty( executionTimeoutSecondsOverride ) ) {
            executionTimeoutSeconds = Integer.valueOf( executionTimeoutSecondsOverride );
        }
    }

    @Override
    public void destroy() {

    }

    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain filterChain
    ) throws IOException, ServletException {
        boolean hasAlreadyFilteredAttribute = request.getAttribute( getAlreadyFilteredAttributeName() ) != null;

        if ( hasAlreadyFilteredAttribute ) {
            filterChain.doFilter( request, response );
        } else {
            request.setAttribute( getAlreadyFilteredAttributeName(), Boolean.TRUE );
            try {
                doFilterInternal( request, response, filterChain );
            } finally {
                request.removeAttribute( getAlreadyFilteredAttributeName() );
            }
        }
    }

    private String getAlreadyFilteredAttributeName() {
        return getClass().getName() + ALREADY_FILTERED_SUFFIX;
    }

    protected String getResponseViaInternalDispatching(
            String relativeUrl,
            ServletRequest request,
            ServletResponse response
    ) throws ServletException, IOException {
        HtmlResponseWrapper responseWrapper = new HtmlResponseWrapper( response );
        request.getRequestDispatcher( relativeUrl )
               .forward(
                       new GetHttpServletRequest( (HttpServletRequest) request ),
                       responseWrapper
               );

        return responseWrapper.getCaptureAsString();
    }

    protected String getResponseViaInternalDispatching(
            URI uri,
            Header[] headers
    ) throws IOException, URISyntaxException {
        ConnectionKeepAliveStrategy keepAliveStrat = new DefaultConnectionKeepAliveStrategy() {

            @Override
            public long getKeepAliveDuration(
                    HttpResponse response,
                    HttpContext context
            ) {
                /*long keepAlive = super.getKeepAliveDuration( response, context );
                if ( keepAlive == -1 ) {
                    // Keep connections alive 5 seconds if a keep-alive value
                    // has not be explicitly set by the server
                    keepAlive = 5000;
                }*/
                return 5000;
            }
        };
        try (
                CloseableHttpClient httpClient = HttpClients.custom()
                                                            .setKeepAliveStrategy( keepAliveStrat )
                                                            .build()
        ) {
            HttpGet httpGet = new HttpGet( uri );
            httpGet.setHeaders( headers );
            RequestConfig requestConfig = RequestConfig.custom()
                                                       .setSocketTimeout( 1000 )
                                                       .setConnectTimeout( 1000 )
                                                       .build();
            httpGet.setConfig( requestConfig );
            HttpEntity entity = httpClient.execute( httpGet ).getEntity();
            return entity != null ? EntityUtils.toString( entity ) : null;
        }
    }

    protected URI buildUri(
            ServletRequest request,
            String relativeUrl,
            Collection<NameValuePair> additionalRequestParams
    ) throws URISyntaxException {
        int questionMarkIndex = relativeUrl.indexOf( '?' );
        String path = questionMarkIndex != -1
                ? relativeUrl.substring( 0, relativeUrl.indexOf( '?' ) )
                : relativeUrl;
        List<NameValuePair> params = new ArrayList<>();
        params.addAll( URLEncodedUtils.parse(
                new URI( relativeUrl ),
                Charset.forName( "UTF-8" )
        ) );
        params.addAll( additionalRequestParams );

        return new URIBuilder()
                .setScheme( request.getScheme() )
                .setHost( request.getLocalName() )
                .setPort( request.getLocalPort() )
                .setPath( path )
                .setParameters( params )
                .build();
    }

    protected Header[] getHeaders( HttpServletRequest request ) {
        List<Header> headers = new ArrayList<>();
        for ( Enumeration<String> e = request.getHeaderNames(); e.hasMoreElements(); ) {
            String name = e.nextElement();
            headers.add( new BasicHeader( name, request.getHeader( name ) ) );
        }
        return headers.toArray( new Header[headers.size()] );
    }

    protected abstract void doFilterInternal(
            ServletRequest request,
            ServletResponse response,
            FilterChain filterChain
    ) throws IOException, ServletException;

    protected <T> List<T> executeInParallel(
            List<Callable<T>> callables
    ) {
        List<Future<T>> futures = new ArrayList<>();
        callables.forEach( c -> futures.add( executorService.submit( c ) ) );

        List<T> result = new ArrayList<>();
        for ( Future<T> future : futures ) {
            try {
                result.add( future.get( executionTimeoutSeconds, TimeUnit.SECONDS ) );
            } catch ( InterruptedException e ) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException( e );
            } catch ( ExecutionException e ) {
                throw new RuntimeException( e.getCause() );
            } catch ( TimeoutException e ) {
                future.cancel( true );
                throw new RuntimeException( e );
            }
        }
        return result;
    }

    private static class GetHttpServletRequest extends HttpServletRequestWrapper {
        private static final String GET_METHOD = "GET";

        public GetHttpServletRequest( HttpServletRequest request ) {
            super( request );
        }

        @Override
        public String getMethod() {
            return GET_METHOD;
        }
    }
}
