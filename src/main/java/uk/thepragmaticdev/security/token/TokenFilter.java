package uk.thepragmaticdev.security.token;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import uk.thepragmaticdev.exception.ApiError;
import uk.thepragmaticdev.exception.ApiException;
import uk.thepragmaticdev.exception.code.AuthCode;

public class TokenFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(TokenFilter.class);

  private final TokenService tokenService;

  public TokenFilter(TokenService tokenService) {
    this.tokenService = tokenService;
  }

  @Override
  protected void doFilterInternal(//
      HttpServletRequest httpServletRequest, //
      HttpServletResponse httpServletResponse, //
      FilterChain filterChain) throws ServletException, IOException {
    var token = tokenService.resolveToken(httpServletRequest);
    try {
      if (tokenService.validateToken(token)) {
        var auth = tokenService.getAuthentication(token);
        SecurityContextHolder.getContext().setAuthentication(auth);
      }
    } catch (ApiException ex) {
      SecurityContextHolder.clearContext();

      var responseBody = new ApiError(//
          AuthCode.ACCESS_TOKEN_INVALID.getStatus(), //
          AuthCode.ACCESS_TOKEN_INVALID.getMessage() //
      );
      LOG.warn("{}", responseBody);
      httpServletResponse.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
      httpServletResponse.setStatus(AuthCode.ACCESS_TOKEN_INVALID.getStatus().value());
      httpServletResponse.getWriter().write(responseBody.toString());
      return;
    }
    filterChain.doFilter(httpServletRequest, httpServletResponse);
  }
}