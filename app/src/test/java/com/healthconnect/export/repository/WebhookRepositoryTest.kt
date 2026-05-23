package com.healthconnect.export.repository

import org.junit.Test
import org.junit.Assert.*

class WebhookRepositoryTest {

    private val repo = WebhookRepository()

    @Test
    fun `empty url is invalid`() {
        assertFalse(repo.isValidWebhookUrl(""))
    }

    @Test
    fun `blank url is invalid`() {
        assertFalse(repo.isValidWebhookUrl("   "))
        assertFalse(repo.isValidWebhookUrl("\t"))
        assertFalse(repo.isValidWebhookUrl("\n"))
    }

    @Test
    fun `valid https url returns true`() {
        assertTrue(repo.isValidWebhookUrl("https://example.com"))
    }

    @Test
    fun `valid http url returns true`() {
        assertTrue(repo.isValidWebhookUrl("http://example.com"))
    }

    @Test
    fun `valid https url with path returns true`() {
        assertTrue(repo.isValidWebhookUrl("https://example.com/api/health-data"))
    }

    @Test
    fun `valid https url with query params returns true`() {
        assertTrue(repo.isValidWebhookUrl("https://hooks.example.com/send?format=json&key=abc"))
    }

    @Test
    fun `valid https url with port returns true`() {
        assertTrue(repo.isValidWebhookUrl("https://example.com:8443/webhook"))
    }

    @Test
    fun `valid ip address url returns true`() {
        assertTrue(repo.isValidWebhookUrl("https://192.168.1.100:8080/webhook"))
    }

    @Test
    fun `url without host is invalid`() {
        assertFalse(repo.isValidWebhookUrl("http://"))
        assertFalse(repo.isValidWebhookUrl("https://"))
    }

    @Test
    fun `ftp protocol is invalid`() {
        assertFalse(repo.isValidWebhookUrl("ftp://files.example.com/data"))
    }

    @Test
    fun `file protocol is invalid`() {
        assertFalse(repo.isValidWebhookUrl("file:///tmp/data.json"))
    }

    @Test
    fun `gibberish string is invalid`() {
        assertFalse(repo.isValidWebhookUrl("not-a-url"))
        assertFalse(repo.isValidWebhookUrl("hello world"))
    }

    @Test
    fun `localhost http url is valid`() {
        assertTrue(repo.isValidWebhookUrl("http://localhost:3000/webhook"))
    }

    @Test
    fun `localhost https with path is valid`() {
        assertTrue(repo.isValidWebhookUrl("https://localhost/api"))
    }
}
