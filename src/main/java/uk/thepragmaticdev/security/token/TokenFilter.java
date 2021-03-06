package uk.thepragmaticdev.security.token;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import uk.thepragmaticdev.exception.ApiError;
import uk.thepragmaticdev.exception.ApiException;
import uk.thepragmaticdev.exception.code.AuthCode;

@Log4j2
public class TokenFilter extends OncePerRequestFilter {

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
      log.warn("{}", responseBody);
      httpServletResponse.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
      httpServletResponse.setStatus(AuthCode.ACCESS_TOKEN_INVALID.getStatus().value());
      httpServletResponse.getWriter().write(responseBody.toString());
      return;
    }
    filterChain.doFilter(httpServletRequest, httpServletResponse);
  }
}