package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	serverEnv = "ANDROID_APK_INSTANT_DEPLOY_SERVER"
	tokenEnv  = "ANDROID_APK_INSTANT_DEPLOY_ACCESS_TOKEN"
)

func main() {
	if err := run(os.Args[1:], os.Stdin, os.Stdout, os.Stderr); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(args []string, stdin io.Reader, stdout, stderr io.Writer) error {
	if len(args) == 0 {
		printUsage(stderr)
		return errors.New("command is required")
	}

	switch args[0] {
	case "help", "-h", "--help":
		printUsage(stdout)
		return nil
	case "upload":
		client, err := newAPIClientFromEnv()
		if err != nil {
			return err
		}
		return runUpload(client, args[1:], stdout, stderr)
	case "apps":
		client, err := newAPIClientFromEnv()
		if err != nil {
			return err
		}
		return runApps(client, args[1:], stdout)
	case "touch_policies":
		client, err := newAPIClientFromEnv()
		if err != nil {
			return err
		}
		return runTouchPolicies(client, args[1:], stdin, stdout)
	case "releases":
		client, err := newAPIClientFromEnv()
		if err != nil {
			return err
		}
		return runReleases(client, args[1:], stdout)
	case "delete_release":
		client, err := newAPIClientFromEnv()
		if err != nil {
			return err
		}
		return runDeleteRelease(client, args[1:], stdin, stdout)
	default:
		printUsage(stderr)
		return fmt.Errorf("unknown command: %s", args[0])
	}
}

func printUsage(w io.Writer) {
	fmt.Fprintln(w, "usage:")
	fmt.Fprintln(w, "  android_apk_instant_deploy upload /path/to/upload.apk")
	fmt.Fprintln(w, "  android_apk_instant_deploy apps")
	fmt.Fprintln(w, "  android_apk_instant_deploy releases --app <package_name>")
	fmt.Fprintln(w, "  android_apk_instant_deploy delete_release <id> --app <package_name> [--yes]")
	fmt.Fprintln(w, "  android_apk_instant_deploy touch_policies --app <package_name>")
}

type apiClient struct {
	baseURL string
	token   string
	http    *http.Client
}

func newAPIClientFromEnv() (*apiClient, error) {
	baseURL := strings.TrimRight(strings.TrimSpace(os.Getenv(serverEnv)), "/")
	if baseURL == "" {
		return nil, fmt.Errorf("%s is required", serverEnv)
	}
	parsed, err := url.Parse(baseURL)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return nil, fmt.Errorf("%s must be an absolute URL", serverEnv)
	}

	token := strings.TrimSpace(os.Getenv(tokenEnv))
	if token == "" {
		return nil, fmt.Errorf("%s is required", tokenEnv)
	}

	return &apiClient{
		baseURL: baseURL,
		token:   token,
		http: &http.Client{
			Timeout: 10 * time.Minute,
		},
	}, nil
}

func (c *apiClient) newRequest(method, path string, body io.Reader) (*http.Request, error) {
	req, err := http.NewRequest(method, c.baseURL+path, body)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	return req, nil
}

func runUpload(client *apiClient, args []string, stdout, stderr io.Writer) error {
	if len(args) != 1 {
		return errors.New("usage: android_apk_instant_deploy upload /path/to/upload.apk")
	}

	apkPath := args[0]
	file, err := os.Open(apkPath)
	if err != nil {
		return err
	}
	defer file.Close()

	info, err := file.Stat()
	if err != nil {
		return err
	}
	if info.IsDir() {
		return fmt.Errorf("%s is a directory", apkPath)
	}

	bodyReader, bodyWriter := io.Pipe()
	multipartWriter := multipart.NewWriter(bodyWriter)
	progress := newProgressWriter(stderr, info.Size())

	go func() {
		defer bodyWriter.Close()

		part, err := multipartWriter.CreateFormFile("file", filepath.Base(apkPath))
		if err != nil {
			_ = bodyWriter.CloseWithError(err)
			return
		}

		if _, err := io.Copy(part, io.TeeReader(file, progress)); err != nil {
			_ = bodyWriter.CloseWithError(err)
			return
		}
		progress.finish()
		if err := multipartWriter.Close(); err != nil {
			_ = bodyWriter.CloseWithError(err)
			return
		}
	}()

	req, err := client.newRequest(http.MethodPost, "/admin/artifacts", bodyReader)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", multipartWriter.FormDataContentType())

	resp, err := client.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if err := decodeErrorResponse(resp); err != nil {
		return err
	}

	fmt.Fprintln(stdout, "DONE")
	return nil
}

type progressWriter struct {
	out       io.Writer
	total     int64
	written   int64
	lastShown int
}

func newProgressWriter(out io.Writer, total int64) *progressWriter {
	writer := &progressWriter{out: out, total: total, lastShown: -1}
	writer.render(0)
	return writer
}

func (w *progressWriter) Write(p []byte) (int, error) {
	n := len(p)
	w.written += int64(n)
	percent := 100
	if w.total > 0 {
		percent = int((w.written * 100) / w.total)
	}
	if percent > 100 {
		percent = 100
	}
	w.render(percent)
	return n, nil
}

func (w *progressWriter) finish() {
	w.render(100)
	fmt.Fprintln(w.out)
}

func (w *progressWriter) render(percent int) {
	if percent == w.lastShown {
		return
	}
	w.lastShown = percent

	const width = 24
	done := percent * width / 100
	var bar strings.Builder
	for i := 0; i < width; i++ {
		switch {
		case i < done:
			bar.WriteByte('=')
		case i == done && percent < 100:
			bar.WriteByte('>')
		default:
			bar.WriteByte(' ')
		}
	}

	fmt.Fprintf(w.out, "\r[%s] %d%%", bar.String(), percent)
}

func runApps(client *apiClient, args []string, stdout io.Writer) error {
	if len(args) != 0 {
		return errors.New("usage: android_apk_instant_deploy apps")
	}

	req, err := client.newRequest(http.MethodGet, "/admin/apps", nil)
	if err != nil {
		return err
	}

	resp, err := client.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if err := decodeErrorResponse(resp); err != nil {
		return err
	}

	var payload appsResponse
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return err
	}

	for _, app := range payload.Apps {
		fmt.Fprintf(stdout, "display_name: %s, package_name: %s\n", app.DisplayName, app.PackageName)
	}
	return nil
}

type appsResponse struct {
	Apps []appListItem `json:"apps"`
}

type appListItem struct {
	DisplayName string `json:"display_name"`
	PackageName string `json:"package_name"`
}

func runReleases(client *apiClient, args []string, stdout io.Writer) error {
	packageName, err := requiredAppOption(args, "usage: android_apk_instant_deploy releases --app <package_name>")
	if err != nil {
		return err
	}

	payload, err := client.listReleases(packageName)
	if err != nil {
		return err
	}

	for _, release := range payload.Releases {
		fmt.Fprintf(
			stdout,
			"id: %d, version_code: %d, version_name: %s, filename: %s, sha256: %s\n",
			release.ID,
			release.VersionCode,
			release.VersionName,
			release.Artifact.Filename,
			release.Artifact.SHA256,
		)
	}
	return nil
}

func runDeleteRelease(client *apiClient, args []string, stdin io.Reader, stdout io.Writer) error {
	releaseID, packageName, yes, err := deleteReleaseArgs(args)
	if err != nil {
		return err
	}

	if !yes {
		fmt.Fprintf(stdout, "Are you sure to delete release %d for %s ? (y/N)\n", releaseID, packageName)
		answer, err := readConfirmation(stdin)
		if err != nil {
			return err
		}
		if answer != "y" && answer != "yes" {
			return errors.New("aborted")
		}
	}

	if err := client.deleteRelease(packageName, releaseID); err != nil {
		return err
	}

	fmt.Fprintln(stdout, "DONE")
	return nil
}

func (c *apiClient) listReleases(packageName string) (appReleasesResponse, error) {
	path := "/admin/apps/" + url.PathEscape(packageName) + "/releases"
	req, err := c.newRequest(http.MethodGet, path, nil)
	if err != nil {
		return appReleasesResponse{}, err
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return appReleasesResponse{}, err
	}
	defer resp.Body.Close()

	if err := decodeErrorResponse(resp); err != nil {
		return appReleasesResponse{}, err
	}

	var payload appReleasesResponse
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return appReleasesResponse{}, err
	}
	return payload, nil
}

func (c *apiClient) deleteRelease(packageName string, releaseID int) error {
	path := fmt.Sprintf("/admin/apps/%s/releases/%d", url.PathEscape(packageName), releaseID)
	req, err := c.newRequest(http.MethodDelete, path, nil)
	if err != nil {
		return err
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	return decodeErrorResponse(resp)
}

type appReleasesResponse struct {
	Releases []releaseListItem `json:"releases"`
}

type releaseListItem struct {
	ID          int          `json:"id"`
	VersionCode int          `json:"version_code"`
	VersionName string       `json:"version_name"`
	Artifact    artifactItem `json:"artifact"`
}

type artifactItem struct {
	Filename string `json:"filename"`
	SHA256   string `json:"sha256"`
}

func runTouchPolicies(client *apiClient, args []string, _ io.Reader, stdout io.Writer) error {
	packageName, err := touchPoliciesPackageName(args)
	if err != nil {
		return err
	}

	path := "/admin/apps/" + url.PathEscape(packageName) + "/touch_policies"
	req, err := client.newRequest(http.MethodPost, path, nil)
	if err != nil {
		return err
	}

	resp, err := client.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if err := decodeErrorResponse(resp); err != nil {
		return err
	}

	var payload touchPoliciesResponse
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return err
	}

	for _, policy := range payload.DevicePolicies {
		name := strings.TrimSpace(policy.DisplayName)
		if name == "" {
			name = policy.Identifier
		}
		fmt.Fprintf(stdout, "policy %s updated\n", name)
	}
	fmt.Fprintln(stdout, "DONE")
	return nil
}

func touchPoliciesPackageName(args []string) (string, error) {
	return requiredAppOption(args, "usage: android_apk_instant_deploy touch_policies --app <package_name>")
}

func requiredAppOption(args []string, usage string) (string, error) {
	if len(args) != 2 || args[0] != "--app" {
		return "", errors.New(usage)
	}
	return validatePackageName(args[1])
}

func validatePackageName(value string) (string, error) {
	packageName := strings.TrimSpace(value)
	if packageName == "" {
		return "", errors.New("--app must not be empty")
	}
	return packageName, nil
}

func deleteReleaseArgs(args []string) (int, string, bool, error) {
	if len(args) != 3 && len(args) != 4 {
		return 0, "", false, errors.New("usage: android_apk_instant_deploy delete_release <id> --app <package_name> [--yes]")
	}

	releaseID, err := parsePositiveInt(args[0])
	if err != nil {
		return 0, "", false, err
	}
	if args[1] != "--app" {
		return 0, "", false, errors.New("usage: android_apk_instant_deploy delete_release <id> --app <package_name> [--yes]")
	}
	packageName, err := validatePackageName(args[2])
	if err != nil {
		return 0, "", false, err
	}

	yes := false
	if len(args) == 4 {
		if args[3] != "--yes" {
			return 0, "", false, errors.New("usage: android_apk_instant_deploy delete_release <id> --app <package_name> [--yes]")
		}
		yes = true
	}

	return releaseID, packageName, yes, nil
}

func parsePositiveInt(value string) (int, error) {
	result, err := strconv.Atoi(value)
	if err != nil || result <= 0 {
		return 0, fmt.Errorf("release id must be a positive integer")
	}
	return result, nil
}

func readConfirmation(stdin io.Reader) (string, error) {
	var line string
	if _, err := fmt.Fscanln(stdin, &line); err != nil {
		if errors.Is(err, io.EOF) {
			return "", nil
		}
		return "", err
	}
	return strings.ToLower(strings.TrimSpace(line)), nil
}

type touchPoliciesResponse struct {
	DevicePolicies []touchedDevicePolicy `json:"device_policies"`
}

type touchedDevicePolicy struct {
	Identifier  string `json:"identifier"`
	DisplayName string `json:"display_name"`
}

func decodeErrorResponse(resp *http.Response) error {
	if resp.StatusCode >= 200 && resp.StatusCode <= 299 {
		return nil
	}

	var payload struct {
		Error struct {
			Code    string `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	body, _ := io.ReadAll(resp.Body)
	if err := json.Unmarshal(body, &payload); err == nil && payload.Error.Message != "" {
		return fmt.Errorf("%s: %s", payload.Error.Code, payload.Error.Message)
	}
	return fmt.Errorf("request failed: HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
}
