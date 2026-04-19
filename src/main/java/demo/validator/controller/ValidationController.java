package demo.validator.controller;

import demo.validator.dto.ValidationResponse;
import demo.validator.service.XmlValidationService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for XML/XSD validation.
 *
 * <p>Accepts raw XML as request body and always returns HTTP 200 with a JSON
 * {@link ValidationResponse}. Technical errors (unparseable XML, etc.) are handled
 * by the {@link demo.validator.exception.GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/xml")
public class ValidationController {

    private final XmlValidationService validationService;

    public ValidationController(XmlValidationService validationService) {
        this.validationService = validationService;
    }

    /**
     * Validates the raw XML body against the bundled order XSD.
     *
     * @param xmlContent raw XML (Content-Type: text/xml or application/xml)
     * @return 200 OK with {@link ValidationResponse}
     */
    @PostMapping(
            value = "/validate",
            consumes = {MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ValidationResponse> validate(@RequestBody String xmlContent) {
        ValidationResponse response = validationService.validate(xmlContent);
        return ResponseEntity.ok(response);
    }
}
