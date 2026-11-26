<?php

namespace App\Enums;

/** Autore di un messaggio in chat_messages. */
enum ChatRole: string
{
    case User = 'USER';
    case Assistant = 'ASSISTANT';
}
