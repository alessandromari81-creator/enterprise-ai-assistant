package it.portfolio.eaa.reimbursement;

import it.portfolio.eaa.common.ApiProblem;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/reimbursements: elenco paginato (page in base 0), filtrabile per stato. */
@RestController
@RequestMapping("/api/reimbursements")
class ReimbursementController {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    /** Risposta paginata del contratto. */
    record ReimbursementPageDto(List<ReimbursementDto> items, int page, int size, long totalItems, int totalPages) {}

    private final ReimbursementRepository repository;

    ReimbursementController(ReimbursementRepository repository) {
        this.repository = repository;
    }

    /**
     * I parametri arrivano come stringhe e si validano a mano: così un valore sbagliato produce un 400 nel
     * formato del contratto, con il nome del campo, e non un errore di conversione del framework.
     */
    @GetMapping
    ReimbursementPageDto list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {

        List<ApiProblem.FieldError> errors = new ArrayList<>();

        ReimbursementStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            try {
                statusFilter = ReimbursementStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                errors.add(new ApiProblem.FieldError("status", "Il valore di status non è valido (OPEN, APPROVED, REJECTED)."));
            }
        }
        int pageIndex = intParam("page", page, 0, 0, Integer.MAX_VALUE, errors);
        int pageSize = intParam("size", size, DEFAULT_SIZE, 1, MAX_SIZE, errors);

        if (!errors.isEmpty()) {
            throw ApiProblem.validation(errors);
        }

        // Più recente per primo; a parità di istante l'id (UUIDv7) fa da spareggio stabile.
        PageRequest request = PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<ReimbursementRequest> result =
                statusFilter == null ? repository.findAll(request) : repository.findByStatus(statusFilter, request);

        return new ReimbursementPageDto(
                result.getContent().stream().map(ReimbursementDto::from).toList(),
                pageIndex,
                pageSize,
                result.getTotalElements(),
                result.getTotalPages());
    }

    private static int intParam(String name, String raw, int defaultValue, int min, int max, List<ApiProblem.FieldError> errors) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            if (value < min || value > max) {
                errors.add(new ApiProblem.FieldError(name, "Il valore di " + name + " deve essere tra " + min + " e " + max + "."));
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException e) {
            errors.add(new ApiProblem.FieldError(name, "Il valore di " + name + " deve essere un numero intero."));
            return defaultValue;
        }
    }
}
