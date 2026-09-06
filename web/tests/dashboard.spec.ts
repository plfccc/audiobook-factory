import { expect, test } from "@playwright/test";

test("以章节为主显示书籍进度，并在选择章节后才展示片段", async ({ page }) => {
  await page.route("**/api/v1/books", async (route) => {
    await route.fulfill({
      json: [
        {
          id: 1,
          title: "蛊真人",
          author: "蛊真人",
          status: "RUNNING",
          chapterCount: 2334,
          completedChapters: 43,
        },
      ],
    });
  });
  await page.route("**/api/v1/books/1/progress", async (route) => {
    await route.fulfill({
      json: {
        bookId: 1,
        completedChapters: 43,
        totalChapters: 2334,
        currentChapter: 44,
        currentChapterTitle: "昔日重现",
        status: "RUNNING",
      },
    });
  });
  await page.route("**/api/v1/books/1/chapters", async (route) => {
    await route.fulfill({
      json: [
        {
          id: 44,
          chapterNumber: 44,
          title: "昔日重现",
          status: "RUNNING",
          segmentCount: 8,
          completedSegments: 4,
          audioUrl: null,
        },
      ],
    });
  });
  await page.route("**/api/v1/tts/models", async (route) => {
    await route.fulfill({ json: [] });
  });
  await page.route("**/api/v1/tts/presets", async (route) => {
    await route.fulfill({ json: [] });
  });

  await page.goto("/");

  await expect(page.getByText("43 / 2334 章")).toBeVisible();
  await expect(page.getByText("当前：第 44 章")).toBeVisible();
  await expect(page.getByText(/片段/)).not.toBeVisible();
});
