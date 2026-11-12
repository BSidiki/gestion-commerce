package com.gestioncommerce.backup;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.backup.BackupDtos.BackupFile;
import com.gestioncommerce.backup.BackupDtos.BackupOverview;
import com.gestioncommerce.backup.BackupDtos.BackupRequest;
import com.gestioncommerce.backup.BackupDtos.BackupView;
import com.gestioncommerce.backup.BackupDtos.RestoreRequest;
import com.gestioncommerce.backup.BackupDtos.RestoreResult;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/backups")
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    /** État des sauvegardes, emplacements proposés et historique. */
    @GetMapping
    BackupOverview overview() {
        return backups.overview();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    BackupView create(@RequestBody(required = false) BackupRequest request) {
        return backups.createManual(request == null ? null : request.directory());
    }

    /** Archives présentes dans un dossier (le dossier local si aucun n'est donné). */
    @GetMapping("/files")
    List<BackupFile> files(@RequestParam(required = false) String directory) {
        return backups.filesIn(directory);
    }

    @PostMapping("/restore")
    RestoreResult restore(@Valid @RequestBody RestoreRequest request) {
        return backups.restore(request.path());
    }
}
