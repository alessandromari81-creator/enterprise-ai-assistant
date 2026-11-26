<?php

namespace App\Http\Requests;

use App\Exceptions\ApiProblem;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Http\UploadedFile;
use Symfony\Component\HttpFoundation\File\UploadedFile as SymfonyUploadedFile;

/**
 * Validazione di POST /api/documents (multipart/form-data, campo "file").
 *
 * Il contratto distingue più errori con status diversi, quindi non bastano le regole di
 * Laravel (che darebbero sempre 400):
 *   - campo mancante / non è un file ........ 400  (regole sotto)
 *   - supera 20 MB (anche per i limiti PHP) .. 413
 *   - non è un PDF ........................... 415
 */
class UploadDocumentRequest extends FormRequest
{
    public function authorize(): bool
    {
        return true;
    }

    /** @return array<string, array<int, string>> */
    public function rules(): array
    {
        return ['file' => ['required', 'file']];
    }

    /**
     * Gira PRIMA delle regole. Se PHP ha già scartato il file perché oltre upload_max_filesize,
     * Laravel lo vedrebbe come "campo mancante" (400): qui lo trasformiamo nel 413 corretto.
     */
    protected function prepareForValidation(): void
    {
        // $this->files contiene ancora gli oggetti Symfony "grezzi": Laravel li converte nella
        // sua UploadedFile solo quando si chiama file(). Si controlla quindi la classe base di
        // Symfony, che copre entrambi i casi (con la sola classe Laravel questo check non
        // scatterebbe su una vera richiesta HTTP, come ha mostrato la prova sul server).
        $file = $this->files->get('file');

        if ($file instanceof SymfonyUploadedFile
            && in_array($file->getError(), [UPLOAD_ERR_INI_SIZE, UPLOAD_ERR_FORM_SIZE], true)) {
            throw ApiProblem::fileTooLarge();
        }
    }

    /** Gira DOPO le regole: il file esiste ed è valido, ora se ne controllano dimensione e tipo. */
    protected function passedValidation(): void
    {
        $file = $this->file('file');

        if ($file->getSize() > config('eaa.upload.max_bytes')) {
            throw ApiProblem::fileTooLarge();
        }

        // getMimeType() guarda il CONTENUTO del file (magic bytes), non l'estensione né l'header
        // dichiarato dal client: rinominare un .exe in .pdf non basta.
        if ($file->getMimeType() !== 'application/pdf') {
            throw ApiProblem::unsupportedMediaType();
        }
    }

    public function pdf(): UploadedFile
    {
        return $this->file('file');
    }
}
