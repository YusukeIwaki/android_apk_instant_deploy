package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func TestRunApps(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/admin/apps" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer test-token" {
			t.Fatalf("Authorization = %q", got)
		}
		_ = json.NewEncoder(w).Encode(appsResponse{
			Apps: []appListItem{
				{DisplayName: "xxx", PackageName: "com.example.xxx"},
				{DisplayName: "yyy", PackageName: "com.example.yyy"},
			},
		})
	}))
	defer server.Close()

	t.Setenv(serverEnv, server.URL)
	t.Setenv(tokenEnv, "test-token")

	var stdout strings.Builder
	if err := run([]string{"apps"}, strings.NewReader(""), &stdout, io.Discard); err != nil {
		t.Fatal(err)
	}

	want := "display_name: xxx, package_name: com.example.xxx\n" +
		"display_name: yyy, package_name: com.example.yyy\n"
	if stdout.String() != want {
		t.Fatalf("stdout = %q, want %q", stdout.String(), want)
	}
}

func TestRunReleases(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/admin/apps/io.github.yusukeiwaki.camscanshare/releases" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		_ = json.NewEncoder(w).Encode(appReleasesResponse{
			Releases: []releaseListItem{
				{
					ID:          134,
					VersionCode: 32,
					VersionName: "1.2.3",
					Artifact: artifactItem{
						Filename: "camscanshare.apk",
						SHA256:   "abc123",
					},
				},
			},
		})
	}))
	defer server.Close()

	t.Setenv(serverEnv, server.URL)
	t.Setenv(tokenEnv, "test-token")

	var stdout strings.Builder
	if err := run([]string{"releases", "--app", "io.github.yusukeiwaki.camscanshare"}, strings.NewReader(""), &stdout, io.Discard); err != nil {
		t.Fatal(err)
	}

	want := "id: 134, version_code: 32, version_name: 1.2.3, filename: camscanshare.apk, sha256: abc123\n"
	if stdout.String() != want {
		t.Fatalf("stdout = %q, want %q", stdout.String(), want)
	}
}

func TestRunDeleteReleaseWithYes(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodDelete || r.URL.Path != "/admin/apps/io.github.yusukeiwaki.camscanshare/releases/134" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	t.Setenv(serverEnv, server.URL)
	t.Setenv(tokenEnv, "test-token")

	var stdout strings.Builder
	if err := run([]string{"delete_release", "134", "--app", "io.github.yusukeiwaki.camscanshare", "--yes"}, strings.NewReader(""), &stdout, io.Discard); err != nil {
		t.Fatal(err)
	}

	if stdout.String() != "DONE\n" {
		t.Fatalf("stdout = %q", stdout.String())
	}
}

func TestRunDeleteReleaseWithPrompt(t *testing.T) {
	called := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		if r.Method != http.MethodDelete || r.URL.Path != "/admin/apps/io.github.yusukeiwaki.camscanshare/releases/134" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	t.Setenv(serverEnv, server.URL)
	t.Setenv(tokenEnv, "test-token")

	var stdout strings.Builder
	if err := run([]string{"delete_release", "134", "--app", "io.github.yusukeiwaki.camscanshare"}, strings.NewReader("y\n"), &stdout, io.Discard); err != nil {
		t.Fatal(err)
	}

	if !called {
		t.Fatal("expected delete request")
	}
	if !strings.Contains(stdout.String(), "Are you sure to delete release 134 for io.github.yusukeiwaki.camscanshare ? (y/N)") {
		t.Fatalf("stdout did not include prompt: %q", stdout.String())
	}
	if !strings.HasSuffix(stdout.String(), "DONE\n") {
		t.Fatalf("stdout = %q", stdout.String())
	}
}

func TestRunTouchPolicies(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/admin/apps/io.github.yusukeiwaki.camscanshare/touch_policies" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Fatal(err)
		}
		if len(body) != 0 {
			t.Fatalf("body = %q, want empty", body)
		}
		_ = json.NewEncoder(w).Encode(touchPoliciesResponse{
			DevicePolicies: []touchedDevicePolicy{
				{Identifier: "drt_1", DisplayName: "SomePolicy1"},
				{Identifier: "drt_3", DisplayName: "SomePolicy3"},
			},
		})
	}))
	defer server.Close()

	t.Setenv(serverEnv, server.URL)
	t.Setenv(tokenEnv, "test-token")

	var stdout strings.Builder
	if err := run([]string{"touch_policies", "--app", "io.github.yusukeiwaki.camscanshare"}, strings.NewReader(""), &stdout, io.Discard); err != nil {
		t.Fatal(err)
	}

	want := "policy SomePolicy1 updated\npolicy SomePolicy3 updated\nDONE\n"
	if stdout.String() != want {
		t.Fatalf("stdout = %q, want %q", stdout.String(), want)
	}
}

func TestRunUpload(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/admin/artifacts" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer test-token" {
			t.Fatalf("Authorization = %q", got)
		}
		reader, err := r.MultipartReader()
		if err != nil {
			t.Fatal(err)
		}
		part, err := reader.NextPart()
		if err != nil {
			t.Fatal(err)
		}
		if part.FormName() != "file" || part.FileName() != "upload.apk" {
			t.Fatalf("part = %s/%s", part.FormName(), part.FileName())
		}
		content, err := io.ReadAll(part)
		if err != nil {
			t.Fatal(err)
		}
		if string(content) != "apk-bytes" {
			t.Fatalf("content = %q", content)
		}
		_, err = reader.NextPart()
		if err != io.EOF {
			t.Fatalf("expected multipart EOF, got %v", err)
		}
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"artifact":{"id":1}}`)
	}))
	defer server.Close()

	t.Setenv(serverEnv, server.URL)
	t.Setenv(tokenEnv, "test-token")

	dir := t.TempDir()
	path := dir + "/upload.apk"
	if err := os.WriteFile(path, []byte("apk-bytes"), 0o600); err != nil {
		t.Fatal(err)
	}

	var stdout strings.Builder
	var stderr strings.Builder
	if err := run([]string{"upload", path}, strings.NewReader(""), &stdout, &stderr); err != nil {
		t.Fatal(err)
	}
	if stdout.String() != "DONE\n" {
		t.Fatalf("stdout = %q", stdout.String())
	}
	if !strings.Contains(stderr.String(), "100%") {
		t.Fatalf("stderr did not include progress: %q", stderr.String())
	}
}
