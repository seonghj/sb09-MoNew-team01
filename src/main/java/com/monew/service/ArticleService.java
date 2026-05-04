package com.monew.service;

import com.monew.client.ArticleFetcher;
import com.monew.dto.request.ArticleSearchCondition;
import com.monew.dto.request.CursorRequest;
import com.monew.dto.response.ArticleDto;
import com.monew.dto.response.CursorPageResponseDto;
import com.monew.entity.Article;
import com.monew.entity.ArticleInterest;
import com.monew.entity.Interest;
import com.monew.entity.enums.ArticleSource;
import com.monew.exception.article.ArticleNotFoundException;
import com.monew.mapper.ArticleMapper;
import com.monew.repository.ArticleInterestRepository;
import com.monew.repository.InterestRepository;
import com.monew.repository.SubscriptionRepository;
import com.monew.repository.article.ArticleQueryRepository;
import com.monew.repository.article.ArticleRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleService {

  private final ArticleRepository articleRepository;
  private final ArticleQueryRepository articleQueryRepository;
  private final InterestRepository interestRepository;

  private final ArticleMapper articleMapper;

  private final List<ArticleFetcher> articleFetchers;

  private final NotificationService notificationService;
  private final SubscriptionRepository subscriptionRepository;


  public void collect() {
    List<Interest> allInterests = interestRepository.findAllWithKeywords();
    if (allInterests.isEmpty()) {
      log.info("[뉴스 기사] 관심사가 없어 수집을 종료합니다.");
      return;
    }

    Map<String, Set<Interest>> keywordToInterestsMap = new HashMap<>();
    for (Interest interest : allInterests) {
      keywordToInterestsMap.computeIfAbsent(interest.getName().toLowerCase(), k -> new HashSet<>()).add(interest);
      for (String kw : interest.getKeywords()) {
        keywordToInterestsMap.computeIfAbsent(kw.toLowerCase(), k -> new HashSet<>()).add(interest);
      }
    }

    Set<String> allKeywords = keywordToInterestsMap.keySet();
    log.info("==================================================");
    log.info("[뉴스 기사] 수집 시작. 키워드 갯수: {}", allKeywords.size());

    List<ArticleDto> totalFetchedDtos = collectByKeyword(allKeywords);
    int totalFetchedCount = totalFetchedDtos.size();

    Map<String, Article> urlToArticleMap = new HashMap<>();
    Map<String, Set<Interest>> urlToInterestsMap = new HashMap<>();

    for (ArticleDto dto : totalFetchedDtos) {
      String url = dto.sourceUrl();
      if (urlToArticleMap.containsKey(url)) continue;

      Set<Interest> matchedInterests = findMatchedInterests(dto, allKeywords, keywordToInterestsMap);

      if (!matchedInterests.isEmpty()) {
        urlToArticleMap.put(url, articleMapper.toEntity(dto));
        urlToInterestsMap.put(url, matchedInterests);
      }
    }

    int apiDuplicateCount = totalFetchedCount - urlToArticleMap.size();

    Set<String> existingUrls = articleRepository.findExistingUrls(new ArrayList<>(urlToArticleMap.keySet()));
    existingUrls.forEach(url -> {
      urlToArticleMap.remove(url);
      urlToInterestsMap.remove(url);
    });

    int dbDuplicateCount = existingUrls.size();
    int targetCount = urlToArticleMap.size();

    log.info("[뉴스 기사] 수집된 기사 중복 제거 - API내 중복: {}건, DB 중복: {}건", apiDuplicateCount, dbDuplicateCount);

    if (urlToArticleMap.isEmpty()) {
      log.info("[뉴스 기사] 새로 저장할 뉴스 기사가 없어 수집을 종료합니다.");
      log.info("==================================================");
      return;
    }

    List<Article> articleList = new ArrayList<>(urlToArticleMap.values());
    articleRepository.saveAll(articleList);
    log.info("[뉴스 기사] DB 저장 완료 - {}건", articleList.size());

    List<ArticleInterest> mappingList = new ArrayList<>();
    for (Article article : articleList) {
      Set<Interest> interests = urlToInterestsMap.get(article.getSourceUrl());
      if (interests != null) {
        for (Interest interest : interests) {
          mappingList.add(ArticleInterest.of(article, interest));
        }
      }
    }

    articleInterestRepository.saveAll(mappingList);

    // 관심사별로 새로 등록된 기사 수를 집계한 뒤 구독자에게 요약 알림을 한 건만 생성합니다.
    sendNotifications(articleList, urlToInterestsMap, allInterests);

    log.info("[뉴스 기사] 수집 완료 - 총 {}건 중 {}건 저장", totalFetchedCount, targetCount);
    log.info("==================================================");
  }

  private List<ArticleDto> collectByKeyword(Set<String> keywords) {
    List<ArticleDto> fetchedItems = new ArrayList<>();

    for (ArticleFetcher fetcher : articleFetchers) {
      try {
        log.info("[뉴스 기사] 외부 API 요청 - {}", fetcher.getSourceName());
        List<ArticleDto> fetched = fetcher.fetch(keywords);
        fetchedItems.addAll(fetched);
        log.info("[뉴스 기사] 외부 API 응답 수신 - {}건", fetched.size());
      } catch (Exception e) {
        log.error("[뉴스 기사] 수집 중 [{}]에서 에러 발생: {}", fetcher.getClass().getSimpleName(), e.getMessage());
      }
    }

    return fetchedItems;
  }

  private Set<Interest> findMatchedInterests(ArticleDto dto, Set<String> allKeywords, Map<String, Set<Interest>> mapping) {
    String targetText = (dto.title() + " " + dto.summary()).toLowerCase();
    Set<Interest> matched = new HashSet<>();

    for (String kw : allKeywords) {
      boolean isMatch = false;
      if (kw.matches("^[a-z]+$") && kw.length() <= 3) {
        isMatch = targetText.matches(".*\\b" + kw + "\\b.*");
      } else {
        isMatch = targetText.contains(kw);
      }

      if (isMatch) {
        matched.addAll(mapping.get(kw));
      }
    }
    return matched;
  }

  public void sendNotifications(List<Article> articleList, Map<String, Set<Interest>> urlToInterestsMap, List<Interest> allInterests) {
    Map<UUID, Integer> interestToCount = new HashMap<>();
    for (Article article : articleList) {
      Set<Interest> interests = urlToInterestsMap.get(article.getSourceUrl());
      if (interests == null) continue;
      for (Interest interest : interests) {
        interestToCount.merge(interest.getId(), 1, Integer::sum);
      }
    }

    Map<UUID, Interest> interestLookup = allInterests.stream()
        .collect(Collectors.toMap(Interest::getId, i -> i));

    for (Map.Entry<UUID, Integer> e : interestToCount.entrySet()) {
      Interest interest = interestLookup.get(e.getKey());
      if (interest == null) continue;

      List<UUID> subscriberIds = subscriptionRepository.findUserIdsByInterestId(interest.getId());
      log.debug("[알림 생성] 관심사: {}, 구독자 수: {}", interest.getName(), subscriberIds.size());

      for (UUID userId : subscriberIds) {
        log.debug("[알림 생성] 사용자 ID: {}, 관심사: {}, 기사 수: {}", userId, interest.getName(), e.getValue());
        notificationService.createNotification(
            userId,
            "[" + interest.getName() + "]와 관련된 기사가 " + e.getValue() + "건 등록되었습니다.",
            com.monew.entity.enums.ResourceType.INTEREST,
            interest.getId()
        );
      }
    }
  }

  @Transactional(readOnly = true)
  public CursorPageResponseDto<ArticleDto> findArticles(
      ArticleSearchCondition condition,
      List<ArticleSource> sourceIn,
      CursorRequest cursorRequest,
      UUID userId) {

    log.info("[뉴스 기사] 조회 시도: userId={}, 검색조건={}, 커서={}", userId, condition, cursorRequest);

    CursorPageResponseDto<ArticleDto> result =
        articleQueryRepository.searchArticlesByCursor(condition, sourceIn, cursorRequest, userId);

    log.info("[뉴스 기사] 조회 완료: userId={}", userId);

    return result;
  }

  @Transactional(readOnly = true)
  public ArticleDto find(UUID articleId) {
    log.info("[뉴스 기사] 단건 조회 시도: articleId={}", articleId);
    Article targetArticle = articleRepository.findByIdAndDeletedAtIsNull(articleId).orElseThrow(()
            -> {
          log.warn("[뉴스 기사] 단건 조회 실패: 존재하지 않는 기사 ID={}", articleId);
          return new ArticleNotFoundException(articleId);
        }
    );
    log.info("[뉴스 기사] 단건 조회 완료: articleId={}", articleId);
    return articleMapper.toDto(targetArticle);
  }

  @Transactional
  public void softDelete(UUID articleId) {
    log.info("[뉴스 기사] 논리 삭제 시도: articleId={}", articleId);
    Article targetArticle = articleRepository.findById(articleId).orElseThrow(()
            -> {
          log.warn("[뉴스 기사] 논리 삭제 실패: 존재하지 않는 기사 ID={}", articleId);
          return new ArticleNotFoundException(articleId);
        }
    );
    targetArticle.updateDeletedAt(Instant.now());
    log.info("[뉴스 기사] 논리 삭제 완료: articleId={}", articleId);
  }

  @Transactional
  public void hardDelete(UUID articleId) {
    log.info("[뉴스 기사] 물리 삭제 시도: articleId={}", articleId);
    Article targetArticle = articleRepository.findById(articleId).orElseThrow(()
            -> {
          log.warn("[뉴스 기사] 물리 삭제 실패: 존재하지 않는 기사 ID={}", articleId);
          return new ArticleNotFoundException(articleId);
        }
    );
    articleRepository.delete(targetArticle);
    log.info("[뉴스 기사] 물리 삭제 완료: articleId={}", articleId);
  }

  @Transactional(readOnly = true)
  public List<String> getSources() {
    log.info("[뉴스 기사] 출처 조회 시도");
    List<ArticleSource> sources = articleQueryRepository.findSources();
    log.info("[뉴스 기사] 출처 조회 성공");
    return sources.stream()
        .map(Enum::name)
        .toList();
  }
}
