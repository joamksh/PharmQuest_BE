package com.pharmquest.pharmquest.domain.medicine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmquest.pharmquest.domain.medicine.data.Medicine;
import com.pharmquest.pharmquest.domain.medicine.data.MedicineCategoryMapper;
import com.pharmquest.pharmquest.domain.medicine.repository.MedRepository;
import com.pharmquest.pharmquest.domain.medicine.repository.MedicineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local development import of original FDA labels, without translation or image dependencies. */
@Service
@Profile("local")
@RequiredArgsConstructor
public class LocalMedicineImportService {
    private final MedicineRepository source;
    private final MedRepository repository;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactionTemplate;

    public record ImportResult(int fetched, int saved, int updated, int duplicates, int invalid,
                               String snapshot, List<Long> savedIds) {}

    // Serializes imports in this single local application instance.
    public synchronized ImportResult importLabels(int limit, int skip) throws IOException {
        if (limit < 1 || limit > 100 || skip < 0 || skip > 25000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..100; skip must be 0..25000");
        }
        String response = source.fetchMedicineData("openfda.product_type:\"HUMAN OTC DRUG\"", limit, skip);
        JsonNode results = mapper.readTree(response).path("results");
        if (!results.isArray()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "FDA results missing");

        Path snapshotDirectory = Path.of(".local", "fda-snapshots");
        Files.createDirectories(snapshotDirectory);
        Path snapshot = Files.createTempFile(snapshotDirectory, "labels-" + Instant.now().toEpochMilli() + "-", ".json");
        Files.writeString(snapshot, response, StandardCharsets.UTF_8);

        // Fetch and archive outside the DB transaction. A failed DB write leaves the source snapshot for diagnosis.
        return transactionTemplate.execute(status -> {
            List<Medicine> medicines = new ArrayList<>();
            List<Medicine> updates = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int duplicates = 0;
            int invalid = 0;
            for (JsonNode label : results) {
                Medicine medicine = fromLabel(label);
                if (medicine == null) { invalid++; continue; }
                if (!seen.add(medicine.getSplSetId())) {
                    duplicates++;
                    continue;
                }
                var existing = repository.findBySplSetId(medicine.getSplSetId());
                if (existing.isPresent()) {
                    duplicates++;
                    Medicine stored = existing.get();
                    if ((stored.getRoute() == null || stored.getRoute().isBlank())
                            && medicine.getRoute() != null && !medicine.getRoute().isBlank()) {
                        stored.setRoute(medicine.getRoute());
                        updates.add(stored);
                    }
                    continue;
                }
                medicines.add(medicine);
            }
            List<Long> ids = repository.saveAllAndFlush(medicines).stream().map(Medicine::getId).toList();
            if (!updates.isEmpty()) repository.saveAllAndFlush(updates);
            return new ImportResult(results.size(), ids.size(), updates.size(), duplicates, invalid, snapshot.toString(), ids);
        });
    }

    static Medicine fromLabel(JsonNode label) {
        String setId = label.path("set_id").asText("");
        if (setId.isBlank()) setId = text(label.at("/openfda/spl_set_id"));
        String name = text(label.at("/openfda/brand_name"));
        String indications = text(label.path("indications_and_usage"));
        if (setId.isBlank() || setId.length() > 255 || name.isBlank() || indications.isBlank()) return null;
        Medicine medicine = new Medicine();
        medicine.setSplSetId(setId);
        medicine.setBrandName(name);
        medicine.setGenericName(text(label.at("/openfda/generic_name")));
        medicine.setSubstanceName(text(label.at("/openfda/substance_name")));
        medicine.setActiveIngredient(text(label.path("active_ingredient")));
        medicine.setPurpose(text(label.path("purpose")));
        medicine.setIndicationsAndUsage(indications);
        medicine.setDosageAndAdministration(text(label.path("dosage_and_administration")));
        medicine.setWarnings(text(label.path("warnings")));
        medicine.setCountry("USA");
        medicine.setRoute(text(label.at("/openfda/route")));
        medicine.setCategory(MedicineCategoryMapper.getCategory(medicine.getPurpose(), medicine.getActiveIngredient(),
                text(label.at("/openfda/pharm_class_epc")), text(label.at("/openfda/route"))));
        // MySQL TEXT is limited by UTF-8 bytes; never truncate the evaluation source silently.
        for (String value : List.of(medicine.getBrandName(), medicine.getGenericName(), medicine.getSubstanceName(),
                medicine.getActiveIngredient(), medicine.getPurpose(), medicine.getIndicationsAndUsage(),
                medicine.getDosageAndAdministration(), medicine.getWarnings())) {
            if (value.getBytes(StandardCharsets.UTF_8).length > 65535) return null;
        }
        return medicine;
    }

    private static String text(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (!node.isArray()) return "";
        List<String> values = new ArrayList<>();
        node.forEach(value -> { if (value.isTextual()) values.add(value.asText()); });
        return String.join("\n", values);
    }
}
