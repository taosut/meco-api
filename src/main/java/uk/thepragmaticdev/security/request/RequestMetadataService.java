package uk.thepragmaticdev.security.request;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FileUtils;
import org.rauschig.jarchivelib.ArchiveEntry;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import ua_parser.Parser;
import uk.thepragmaticdev.account.Account;
import uk.thepragmaticdev.email.EmailService;
import uk.thepragmaticdev.exception.ApiException;
import uk.thepragmaticdev.exception.code.CriticalCode;
import uk.thepragmaticdev.log.security.SecurityLogService;

@Log4j2
@Service
public class RequestMetadataService {

  private final String databaseName;

  private final String databaseUrl;

  private final String databaseDirectory;

  private final GeoMetadataService geoMetadataService;

  private final EmailService emailService;

  private final SecurityLogService securityLogService;

  private DatabaseReader databaseReader;

  private DatabaseReaderFactory databaseReaderFactory;

  /**
   * Service for extracting and verifying client geolocation and device metadata
   * from a given request.
   * 
   * @param databaseName       The GeoLite2 database name
   * @param databaseUrl        The GeoLite2 database remote url
   * @param databaseDirectory  The local directory of the GeoLite2 database
   * @param emailService       The service for sending emails
   * @param securityLogService The service for finding security logs
   */
  @Autowired(required = false)
  public RequestMetadataService(//
      @Value("${geolite2.name}") String databaseName, //
      @Value("${geolite2.permalink}") String databaseUrl, //
      @Value("${geolite2.directory}") String databaseDirectory, //
      GeoMetadataService geoMetadataService, EmailService emailService, //
      @Lazy SecurityLogService securityLogService) {
    this(databaseName, databaseUrl, databaseDirectory, geoMetadataService, emailService, securityLogService,
        db -> new DatabaseReader.Builder(db.toFile()).build());
  }

  /**
   * Service for extracting and verifying client geolocation and device metadata
   * from a given request.
   * 
   * @param databaseName          The GeoLite2 database name
   * @param databaseUrl           The GeoLite2 database remote url
   * @param databaseDirectory     The local directory of the GeoLite2 database
   * @param emailService          The service for sending emails
   * @param securityLogService    The service for finding security logs
   * @param databaseReaderFactory A factory for creating a database reader
   */
  @Autowired(required = false)
  RequestMetadataService(//
      @Value("${geolite2.name}") String databaseName, //
      @Value("${geolite2.permalink}") String databaseUrl, //
      @Value("${geolite2.directory}") String databaseDirectory, //
      GeoMetadataService geoMetadataService, EmailService emailService, //
      @Lazy SecurityLogService securityLogService, //
      DatabaseReaderFactory databaseReaderFactory) {
    this.databaseName = databaseName;
    this.databaseUrl = databaseUrl;
    this.databaseDirectory = databaseDirectory;
    this.geoMetadataService = geoMetadataService;
    this.emailService = emailService;
    this.securityLogService = securityLogService;
    this.databaseReaderFactory = databaseReaderFactory;
  }

  /**
   * Load a GeoLite2 database from an existing local file. If a local file does
   * not exist it will attempt to download the database from an origin server.
   * 
   * @return True if database loaded successfully
   */
  public boolean loadDatabase() {
    try {
      var database = fetchDatabase().orElseThrow(() -> new ApiException(CriticalCode.GEOLITE_DOWNLOAD_ERROR));
      this.databaseReader = databaseReaderFactory.create(database);
      log.info("GeoLite2 database loaded");
    } catch (IOException ex) {
      log.error("Failed to load GeoLite2 database: {}", ex.getMessage());
      return false;
    }
    return true;
  }

  private Optional<Path> fetchDatabase() throws IOException {
    final var connectionTimeout = 10000;
    final var readTimeout = 10000;
    var localDatabase = findLocalDatabase();
    if (!localDatabase.isPresent()) {
      log.info("Downloading GeoLite2 database from remote");
      var extension = ".tar.gz";
      var destination = Paths.get(databaseDirectory.concat(databaseName).concat(extension));
      FileUtils.copyURLToFile(new URL(databaseUrl), destination.toFile(), connectionTimeout, readTimeout);
      localDatabase = extractDatabase(destination);
    }
    return localDatabase;
  }

  private Optional<Path> findLocalDatabase() throws IOException {
    try (Stream<Path> files = Files.find(Paths.get(databaseDirectory), Integer.MAX_VALUE,
        (path, basicFileAttributes) -> path.toFile().getName().matches(databaseName))) {
      return files.findFirst();
    }
  }

  private Optional<Path> extractDatabase(Path destination) throws IOException {
    var archiver = ArchiverFactory.createArchiver(destination.toFile());
    var stream = archiver.stream(destination.toFile());
    ArchiveEntry entry;
    while ((entry = stream.getNextEntry()) != null) {
      if (entry.getName().contains(databaseName)) {
        log.info("Found database: {}", entry.getName());
        entry.extract(destination.getParent().toFile());
        return Optional.of(destination.getParent().resolve(entry.getName()));
      }
    }
    // TODO delete archive file
    stream.close();
    return Optional.empty();
  }

  /**
   * Extracts client geolocation and device metadata from the request. If the
   * request does not match an existing request we send an email notification.
   * 
   * @param account The account in which to verify request
   * @param request The request information for HTTP servlets
   * @return The geolocation and device metadata
   */
  public Optional<RequestMetadata> verifyRequest(Account account, HttpServletRequest request) {
    var requestMetadata = extractRequestMetadata(request);
    requestMetadata.ifPresent(r -> verifyRequestMetadata(account, r));
    return requestMetadata;
  }

  private void verifyRequestMetadata(Account account, RequestMetadata requestMetadata) {
    if (!matchesExistingRequestMetadata(account, requestMetadata)) {
      var securityLog = securityLogService.unrecognizedDevice(account, requestMetadata);
      emailService.sendUnrecognizedDevice(account, securityLog);
      log.warn("Unrecognized device {} detected for account {}", requestMetadata, account.getId());
    } else {
      securityLogService.signin(account);
    }
  }

  private boolean matchesExistingRequestMetadata(Account account, RequestMetadata requestMetadata) {
    var securityLogs = securityLogService.findAllByAccountId(account);
    return securityLogs.stream().anyMatch(log -> metaDataMatches(requestMetadata, log.getRequestMetadata()));
  }

  private boolean metaDataMatches(RequestMetadata request, RequestMetadata existing) {
    if (request == null && existing == null) {
      return true;
    }
    if (request == null || existing == null) {
      return false;
    }
    return Objects.equals(existing.getGeoMetadata(), request.getGeoMetadata())
        && Objects.equals(existing.getDeviceMetadata(), request.getDeviceMetadata());
  }

  /**
   * Finds client geolocation and device metadata from the given request. If an
   * error occurs it will return empty.
   * 
   * @param request The request information for HTTP servlets
   * @return The geolocation and device metadata
   */
  public Optional<RequestMetadata> extractRequestMetadata(HttpServletRequest request) {
    try {
      var ip = extractIp(request);
      var geoMetadata = geoMetadataService.extractGeoMetadata(ip, databaseReader);
      var deviceMetadata = extractDeviceMetadata(request);
      return Optional.of(new RequestMetadata(//
          ip, //
          geoMetadata, //
          deviceMetadata));
    } catch (GeoIp2Exception ex) {
      log.warn("IP address not present in the database {}", ex.getMessage());
    } catch (IOException ex) {
      log.warn("IP address of host could not be determined {}", ex.getMessage());
    }
    return Optional.empty();
  }

  private DeviceMetadata extractDeviceMetadata(HttpServletRequest request) throws IOException {
    var client = new Parser().parse(request.getHeader("user-agent"));
    return new DeviceMetadata(//
        client.os.family, //
        client.os.major, //
        client.os.minor, //
        client.userAgent.family, //
        client.userAgent.major, //
        client.userAgent.minor//
    );
  }

  private String extractIp(HttpServletRequest request) {
    var clientXForwardedForIp = request.getHeader("X-FORWARDED-FOR");
    if (clientXForwardedForIp == null) {
      return request.getRemoteAddr();
    } else {
      return parseXForwardedHeader(clientXForwardedForIp);
    }
  }

  private String parseXForwardedHeader(String header) {
    return header.split(" *, *")[0];
  }
}